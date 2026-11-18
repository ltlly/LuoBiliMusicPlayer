// A GUI-subsystem launcher. Only the first launch of a given payload opens its CAB resource.
#include <windows.h>
#include <shlobj.h>
#include <aclapi.h>
#include <sddl.h>
#include <bcrypt.h>
#include <fdi.h>
#include <stdio.h>
#include <stdlib.h>
#include <wchar.h>
#include "bundle.h"

static const BYTE *cab;
static DWORD cab_size;
static const wchar_t *staging;
static DWORD failure;

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
    return directory(root) && regular(root, L"\\asuka-app.json") && regular(root, L"\\BiliMusicPlayer.exe") && regular(root, L"\\libmpv-2.dll")
        && regular(root, L"\\WebView2\\msedgewebview2.exe")
        && regular(root, L"\\WebView2\\EBWebView\\x64\\EmbeddedBrowserWebView.dll");
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

// Fixed Version 120+ requires these rights for Windows 10 renderer AppContainers.
// Set them before extraction, so only the fixed-runtime tree inherits the access.
static BOOL allow_webview_sandbox(wchar_t *path) {
    const wchar_t *sids[] = {L"S-1-15-2-1", L"S-1-15-2-2"};
    PSID identities[2] = {NULL, NULL};
    EXPLICIT_ACCESSW grant[2] = {0};
    PACL old_acl = NULL, new_acl = NULL;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    DWORD err = GetNamedSecurityInfoW(path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                                     NULL, NULL, &old_acl, NULL, &descriptor);
    for (int i = 0; i < 2 && err == ERROR_SUCCESS; ++i) {
        if (!ConvertStringSidToSidW(sids[i], &identities[i])) { err = GetLastError(); break; }
        grant[i].grfAccessPermissions = FILE_GENERIC_READ | FILE_GENERIC_EXECUTE;
        grant[i].grfAccessMode = GRANT_ACCESS;
        grant[i].grfInheritance = SUB_CONTAINERS_AND_OBJECTS_INHERIT;
        grant[i].Trustee.TrusteeForm = TRUSTEE_IS_SID;
        grant[i].Trustee.ptstrName = (LPWSTR)identities[i];
    }
    if (!err) err = SetEntriesInAclW(2, grant, old_acl, &new_acl);
    if (!err) err = SetNamedSecurityInfoW(path, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                                           NULL, NULL, new_acl, NULL);
    LocalFree(new_acl); LocalFree(descriptor);
    LocalFree(identities[0]); LocalFree(identities[1]);
    if (err) SetLastError(err);
    return !err;
}

typedef struct { HANDLE output; LONG position; } CabStream;

static FNALLOC(cab_alloc) { return malloc(cb); }
static FNFREE(cab_free) { free(pv); }
static FNOPEN(cab_open) {
    (void)oflag; (void)pmode;
    if (strcmp(pszFile, "payload.cab")) return -1;
    CabStream *s = calloc(1, sizeof(*s));
    return s ? (INT_PTR)s : -1;
}
static FNREAD(cab_read) {
    CabStream *s = (CabStream *)hf;
    if (cb > cab_size - (DWORD)s->position) cb = cab_size - (DWORD)s->position;
    memcpy(pv, cab + s->position, cb);
    s->position += cb;
    return cb;
}
static FNSEEK(cab_seek) {
    CabStream *s = (CabStream *)hf;
    LONGLONG base = seektype == SEEK_SET ? 0 : seektype == SEEK_CUR ? (LONGLONG)s->position : (LONGLONG)cab_size;
    LONGLONG next = base + dist;
    if (next < 0 || next > cab_size) return -1;
    return s->position = (LONG)next;
}
static FNWRITE(cab_write) {
    CabStream *s = (CabStream *)hf;
    DWORD n = 0;
    if (WriteFile(s->output, pv, cb, &n, NULL) && n == cb) return n;
    failure = GetLastError();
    return (UINT)-1;
}
static FNCLOSE(cab_close) {
    CabStream *s = (CabStream *)hf;
    BOOL ok = !s->output || CloseHandle(s->output);
    free(s);
    return ok ? 0 : -1;
}

static HANDLE new_member(const char *name) {
    int count = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, name, -1, NULL, 0);
    if (!count) return INVALID_HANDLE_VALUE;
    wchar_t *path = concat(staging, L"\\");
    size_t prefix = wcslen(path);
    wchar_t *full = realloc(path, (prefix + count) * sizeof(*path));
    if (!full) { free(path); SetLastError(ERROR_OUTOFMEMORY); return INVALID_HANDLE_VALUE; }
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, name, -1, full + prefix, count);
    HANDLE file = INVALID_HANDLE_VALUE;
    wchar_t *part = full + prefix;
    for (;;) {
        wchar_t *end = wcschr(part, L'\\');
        if (end) *end = 0;
        if (!*part || wcspbrk(part, L"/:") || !wcscmp(part, L".") || !wcscmp(part, L"..")) {
            SetLastError(ERROR_INVALID_NAME); break;
        }
        if (!end) { file = CreateFileW(full, GENERIC_WRITE, 0, NULL, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL); break; }
        if (!make_dir(full)) break;
        *end = L'\\'; part = end + 1;
    }
    free(full);
    return file;
}

static FNFDINOTIFY(cab_notify) {
    if (fdint == fdintCOPY_FILE) {
        HANDLE out = new_member(pfdin->psz1);
        if (out == INVALID_HANDLE_VALUE) { failure = GetLastError(); return -1; }
        CabStream *s = calloc(1, sizeof(*s));
        if (!s) { CloseHandle(out); failure = ERROR_OUTOFMEMORY; return -1; }
        s->output = out;
        return (INT_PTR)s;
    }
    if (fdint == fdintCLOSE_FILE_INFO) return cab_close(pfdin->hf) == 0;
    return fdint == fdintNEXT_CABINET || fdint == fdintPARTIAL_FILE ? -1 : 0;
}

static BOOL unpack(HINSTANCE instance, wchar_t *stage) {
    HRSRC resource = FindResourceW(instance, MAKEINTRESOURCEW(101), RT_RCDATA);
    if (!resource) return FALSE;
    cab_size = SizeofResource(instance, resource);
    cab = LockResource(LoadResource(instance, resource));
    if (!cab || !cab_size || cab_size > LONG_MAX) { SetLastError(ERROR_BAD_FORMAT); return FALSE; }
    BCRYPT_ALG_HANDLE alg;
    BYTE hash[32];
    if (BCryptOpenAlgorithmProvider(&alg, BCRYPT_SHA256_ALGORITHM, NULL, 0)) return FALSE;
    NTSTATUS status = BCryptHash(alg, NULL, 0, (PUCHAR)cab, cab_size, hash, sizeof(hash));
    BCryptCloseAlgorithmProvider(alg, 0);
    char hex[65];
    if (status) { SetLastError(ERROR_INVALID_DATA); return FALSE; }
    for (size_t i = 0; i < sizeof(hash); ++i) sprintf(hex + i * 2, "%02x", hash[i]);
    if (strcmp(hex, BUNDLE_DIGEST)) { SetLastError(ERROR_CRC); return FALSE; }
    wchar_t *web = concat(stage, L"\\WebView2");
    BOOL ok = make_dir(web) && allow_webview_sandbox(web);
    free(web);
    if (!ok) return FALSE;
    ERF err = {0};
    HFDI fdi = FDICreate(cab_alloc, cab_free, cab_open, cab_read, cab_write, cab_close, cab_seek, cpuUNKNOWN, &err);
    if (!fdi) { SetLastError(ERROR_OUTOFMEMORY); return FALSE; }
    staging = stage;
    ok = FDICopy(fdi, "payload.cab", "", 0, cab_notify, NULL, NULL);
    FDIDestroy(fdi);
    if (!ok) SetLastError(failure ? failure : ERROR_INVALID_DATA);
    return ok;
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
    wchar_t *web = concat(dest, L"\\WebView2");
    DWORD code = ERROR_SUCCESS;
    BOOL ok = make_dir(app) && make_dir(cache);
    wchar_t *long_cache = concat(L"\\\\?\\", cache), *long_dest = concat(L"\\\\?\\", dest);
    HANDLE lease = ok ? ensure_installed(instance, long_cache, long_dest) : INVALID_HANDLE_VALUE;
    ok = lease != INVALID_HANDLE_VALUE;
    if (ok && wcslen(web) >= MAX_PATH) {
        // Asuka queries the fixed-browser path into a MAX_PATH buffer.
        wchar_t short_web[MAX_PATH];
        DWORD n = GetShortPathNameW(web, short_web, MAX_PATH);
        if (n && n < MAX_PATH) { free(web); web = _wcsdup(short_web); }
        else { SetLastError(ERROR_FILENAME_EXCED_RANGE); ok = FALSE; }
    }
    if (ok) ok = SetEnvironmentVariableW(L"WEBVIEW2_BROWSER_EXECUTABLE_FOLDER", web);
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
    free(cmd); free(first); free(start); free(web); free(exe); free(long_dest); free(long_cache);
    free(dest); free(cache); free(app);
    return (int)code;
}
