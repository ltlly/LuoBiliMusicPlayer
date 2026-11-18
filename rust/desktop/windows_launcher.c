// A GUI-subsystem launcher. Only the first launch of a given payload decodes its embedded XZ stream.
#include <windows.h>
#include <shlobj.h>
#include <bcrypt.h>
#include <lzma.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include "bundle.h"

static wchar_t *concat(const wchar_t *a, const wchar_t *b) {
    size_t n = wcslen(a) + wcslen(b) + 1;
    wchar_t *p = malloc(n * sizeof(*p));
    if (!p) ExitProcess(ERROR_OUTOFMEMORY);
    wcscpy(p, a);
    wcscat(p, b);
    return p;
}

static BOOL directory(const wchar_t *path) {
    DWORD a = GetFileAttributesW(path);
    return a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY) && !(a & FILE_ATTRIBUTE_REPARSE_POINT);
}

static BOOL make_dir(const wchar_t *path) {
    return CreateDirectoryW(path, NULL) || (GetLastError() == ERROR_ALREADY_EXISTS && directory(path));
}

static BOOL regular(const wchar_t *root, const wchar_t *name) {
    wchar_t *p = concat(root, name);
    DWORD attrs = GetFileAttributesW(p);
    free(p);
    return attrs != INVALID_FILE_ATTRIBUTES && !(attrs & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT));
}

static BOOL prepared(const wchar_t *root) {
    if (!directory(root)) return FALSE;
    wchar_t *prefix = concat(root, L"\\");
    BOOL ok = TRUE;
    for (size_t i = 0; ok && i < ARRAYSIZE(members); ++i) {
        wchar_t *path = concat(prefix, members[i].name);
        WIN32_FILE_ATTRIBUTE_DATA data;
        ok = GetFileAttributesExW(path, GetFileExInfoStandard, &data)
             && !(data.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))
             && (((ULONGLONG)data.nFileSizeHigh << 32) | data.nFileSizeLow) == members[i].size;
        free(path);
    }
    free(prefix);
    return ok;
}

// Only used on our unpublished staging directory, or a damaged version cache.
// Never recurse through junctions or other reparse points.
static BOOL remove_tree(const wchar_t *root, BOOL keep_marker) {
    DWORD attrs = GetFileAttributesW(root);
    if (attrs == INVALID_FILE_ATTRIBUTES) return GetLastError() == ERROR_FILE_NOT_FOUND || GetLastError() == ERROR_PATH_NOT_FOUND;
    if (!(attrs & FILE_ATTRIBUTE_DIRECTORY)) return DeleteFileW(root);
    if (attrs & FILE_ATTRIBUTE_REPARSE_POINT) return RemoveDirectoryW(root);
    wchar_t *prefix = concat(root, L"\\"), *pattern = concat(prefix, L"*");
    WIN32_FIND_DATAW data;
    HANDLE iter = FindFirstFileW(pattern, &data);
    BOOL ok = iter != INVALID_HANDLE_VALUE || GetLastError() == ERROR_FILE_NOT_FOUND;
    if (iter != INVALID_HANDLE_VALUE) {
        do {
            if (!wcscmp(data.cFileName, L".") || !wcscmp(data.cFileName, L"..")) continue;
            if (keep_marker && !wcscmp(data.cFileName, L".bili-bundle")) continue;
            wchar_t *child = concat(prefix, data.cFileName);
            ok = remove_tree(child, FALSE);
            free(child);
        } while (ok && FindNextFileW(iter, &data));
        FindClose(iter);
    }
    if (ok && keep_marker) {
        wchar_t *marker = concat(prefix, L".bili-bundle");
        ok = DeleteFileW(marker);
        free(marker);
    }
    free(pattern); free(prefix);
    return ok && RemoveDirectoryW(root);
}

// Member names come from the build-time validated index, relative to this staging directory.
static HANDLE new_member(const wchar_t *prefix, const wchar_t *name) {
    wchar_t *path = concat(prefix, name);
    wchar_t *component = path + wcslen(prefix);
    HANDLE file = INVALID_HANDLE_VALUE;
    for (;;) {
        wchar_t *end = wcschr(component, L'\\');
        if (!end) {
            file = CreateFileW(path, GENERIC_WRITE, 0, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
            break;
        }
        *end = 0;
        if (!make_dir(path)) break;
        *end = L'\\';
        component = end + 1;
    }
    free(path);
    return file;
}

static DWORD write_member(lzma_stream *decoder, HANDLE file, ULONGLONG size) {
    BYTE buffer[65536];
    while (size) {
        size_t want = size > sizeof(buffer) ? sizeof(buffer) : (size_t)size;
        decoder->next_out = buffer;
        decoder->avail_out = want;
        lzma_ret status = lzma_code(decoder, LZMA_FINISH);
        DWORD got = (DWORD)(want - decoder->avail_out);
        if (!got || (status != LZMA_OK && status != LZMA_STREAM_END)) return ERROR_INVALID_DATA;
        DWORD written = 0;
        if (!WriteFile(file, buffer, got, &written, NULL)) return GetLastError();
        if (written != got) return ERROR_WRITE_FAULT;
        size -= got;
    }
    return ERROR_SUCCESS;
}

static BOOL unpack(HINSTANCE instance, wchar_t *stage) {
    HRSRC resource = FindResourceW(instance, MAKEINTRESOURCEW(101), RT_RCDATA);
    if (!resource) return FALSE;
    DWORD length = SizeofResource(instance, resource);
    const BYTE *payload = LockResource(LoadResource(instance, resource));
    if (!payload || !length) { SetLastError(ERROR_BAD_FORMAT); return FALSE; }
    BCRYPT_ALG_HANDLE alg;
    BYTE hash[32];
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, NULL, 0)) return FALSE;
    NTSTATUS status = BCryptHash(alg, NULL, 0, (PUCHAR)payload, length, hash, sizeof(hash));
    BCryptCloseAlgorithmProvider(alg, 0);
    if (status) { SetLastError(ERROR_INVALID_DATA); return FALSE; }
    char hex[65];
    for (size_t i = 0; i < sizeof(hash); ++i) sprintf(hex + i * 2, "%02x", hash[i]);
    if (strcmp(hex, BUNDLE_DIGEST)) { SetLastError(ERROR_CRC); return FALSE; }

    lzma_stream decoder = LZMA_STREAM_INIT;
    if (lzma_stream_decoder(&decoder, 64U << 20, 0) != LZMA_OK) {
        SetLastError(ERROR_OUTOFMEMORY); return FALSE;
    }
    decoder.next_in = payload;
    decoder.avail_in = length;
    wchar_t *prefix = concat(stage, L"\\");
    DWORD error = ERROR_SUCCESS;
    for (size_t i = 0; !error && i < ARRAYSIZE(members); ++i) {
        HANDLE file = new_member(prefix, members[i].name);
        if (file == INVALID_HANDLE_VALUE) { error = GetLastError(); break; }
        error = write_member(&decoder, file, members[i].size);
        if (!CloseHandle(file) && !error) error = GetLastError();
    }
    if (!error) {
        BYTE extra;
        decoder.next_out = &extra;
        decoder.avail_out = 1;
        if (lzma_code(&decoder, LZMA_FINISH) != LZMA_STREAM_END || !decoder.avail_out || decoder.avail_in)
            error = ERROR_INVALID_DATA;
    }
    free(prefix);
    lzma_end(&decoder);
    if (error) SetLastError(error);
    return !error;
}

// Every live host owns an inherited read handle on this file. Exclusive open is
// possible only after all launchers and hosts using that version have exited.
static HANDLE marker_handle(const wchar_t *root, DWORD share, DWORD creation, BOOL inherit) {
    wchar_t *name = concat(root, L"\\.bili-bundle");
    SECURITY_ATTRIBUTES sa = { sizeof(sa), NULL, inherit };
    HANDLE h = CreateFileW(name, GENERIC_READ, share, &sa, creation, FILE_FLAG_OPEN_REPARSE_POINT, NULL);
    free(name);
    return h;
}

static BOOL remove_unused(const wchar_t *root) {
    if (!regular(root, L"\\.bili-bundle")) return FALSE;
    HANDLE h = marker_handle(root, 0, OPEN_EXISTING, FALSE);
    if (h == INVALID_HANDLE_VALUE) return FALSE;
    CloseHandle(h);
    return remove_tree(root, TRUE);
}

static void collect_old(const wchar_t *cache) {
    wchar_t *prefix = concat(cache, L"\\"), *pattern = concat(prefix, L"*");
    WIN32_FIND_DATAW entry;
    HANDLE iter = FindFirstFileW(pattern, &entry);
    if (iter != INVALID_HANDLE_VALUE) {
        do {
            if (!(entry.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) || (entry.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)) continue;
            const wchar_t *name = entry.cFileName;
            if (wcsspn(name, L"0123456789abcdef") != 24 || !wcscmp(name, BUNDLE_KEY)) continue;
            if (name[24] && wcscmp(name + 24, L".new")) continue;
            wchar_t *path = concat(prefix, name);
            remove_unused(path);
            free(path);
        } while (FindNextFileW(iter, &entry));
        FindClose(iter);
    }
    free(pattern); free(prefix);
}

static HANDLE ensure_installed(HINSTANCE instance, const wchar_t *cache, wchar_t *dest) {
    // Serialize publication, acquisition of host leases, and collection across user sessions.
    wchar_t *lockname = concat(cache, L"\\bundle.lock"), *stage = concat(dest, L".new");
    HANDLE lock = CreateFileW(lockname, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                              NULL, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    free(lockname);
    if (lock == INVALID_HANDLE_VALUE) { free(stage); return INVALID_HANDLE_VALUE; }
    OVERLAPPED at = {0};
    BOOL ok = LockFileEx(lock, LOCKFILE_EXCLUSIVE_LOCK, 0, 1, 0, &at);
    if (ok && !prepared(dest)) {
        // Only remove an owned stage or the empty directory left between its creation and marking.
        if (GetFileAttributesW(stage) != INVALID_FILE_ATTRIBUTES)
            ok = directory(stage) && (RemoveDirectoryW(stage) || remove_unused(stage));
        if (ok) ok = make_dir(stage);
        if (ok) {
            HANDLE marker = marker_handle(stage, 0, CREATE_NEW, FALSE);
            ok = marker != INVALID_HANDLE_VALUE && CloseHandle(marker);
        }
        if (ok) ok = unpack(instance, stage) && prepared(stage);
        if (ok && GetFileAttributesW(dest) != INVALID_FILE_ATTRIBUTES) ok = directory(dest) && remove_unused(dest);
        if (ok) ok = MoveFileExW(stage, dest, MOVEFILE_WRITE_THROUGH);
    }
    HANDLE lease = ok ? marker_handle(dest, FILE_SHARE_READ, OPEN_EXISTING, TRUE) : INVALID_HANDLE_VALUE;
    DWORD err = GetLastError();
    if (lease != INVALID_HANDLE_VALUE) collect_old(cache);
    CloseHandle(lock); free(stage);
    if (lease == INVALID_HANDLE_VALUE) SetLastError(err);
    return lease;
}

static BOOL start_host(wchar_t *exe, wchar_t *cmd, HANDLE lease, PROCESS_INFORMATION *child) {
    STARTUPINFOEXW startup = {0};
    startup.StartupInfo.cb = sizeof(startup);
    SIZE_T bytes = 0;
    InitializeProcThreadAttributeList(NULL, 1, 0, &bytes);
    startup.lpAttributeList = malloc(bytes);
    if (!startup.lpAttributeList) { SetLastError(ERROR_OUTOFMEMORY); return FALSE; }
    BOOL ok = InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &bytes);
    if (ok) {
        ok = UpdateProcThreadAttribute(startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST,
                                       &lease, sizeof(lease), NULL, NULL)
          && CreateProcessW(exe, cmd, NULL, NULL, TRUE, EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW, NULL, NULL,
                            &startup.StartupInfo, child);
        DeleteProcThreadAttributeList(startup.lpAttributeList);
    }
    free(startup.lpAttributeList);
    return ok;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR arguments, int show) {
    (void)previous; (void)show;
    PWSTR local = NULL;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_LocalAppData, KF_FLAG_CREATE, NULL, &local))) return ERROR_PATH_NOT_FOUND;
    wchar_t *app = concat(local, L"\\BiliMusicPlayer");
    CoTaskMemFree(local);
    wchar_t *cache = concat(app, L"\\bin");
    wchar_t *dest = concat(cache, L"\\" BUNDLE_KEY);
    wchar_t *exe = concat(dest, L"\\BiliMusicPlayer.exe");
    DWORD code = ERROR_SUCCESS;
    BOOL ok = make_dir(app) && make_dir(cache);
    wchar_t *long_cache = concat(L"\\\\?\\", cache), *long_dest = concat(L"\\\\?\\", dest);
    HANDLE lease = ok ? ensure_installed(instance, long_cache, long_dest) : INVALID_HANDLE_VALUE;
    ok = lease != INVALID_HANDLE_VALUE;
    wchar_t *start = concat(L"\"", exe), *first = concat(start, L"\" "), *cmd = concat(first, arguments);
    PROCESS_INFORMATION child = {0};
    if (ok) ok = start_host(exe, cmd, lease, &child);
    if (ok) {
        CloseHandle(child.hThread);
        WaitForSingleObject(child.hProcess, INFINITE);
        GetExitCodeProcess(child.hProcess, &code);
        CloseHandle(child.hProcess);
    } else {
        code = GetLastError();
        if (!code) code = ERROR_INVALID_DATA;
        wchar_t message[210];
        swprintf(message, 210, L"播放器启动失败（Windows 错误 %lu）。请检查本地缓存目录是否可写、磁盘空间是否充足。", code);
        MessageBoxW(NULL, message, L"哔哩音乐", MB_OK | MB_ICONERROR);
    }
    if (lease != INVALID_HANDLE_VALUE) CloseHandle(lease);
    free(cmd); free(first); free(start); free(exe); free(long_dest); free(long_cache);
    free(dest); free(cache); free(app);
    return (int)code;
}
