-- Room schema shared by the Android and desktop releases.
CREATE TABLE IF NOT EXISTS songs (
    id TEXT NOT NULL,
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    album TEXT,
    duration INTEGER NOT NULL,
    coverUrl TEXT NOT NULL,
    localPath TEXT,
    audioUrl TEXT,
    cid INTEGER NOT NULL,
    bvid TEXT NOT NULL,
    aid INTEGER NOT NULL,
    uploaderId INTEGER NOT NULL,
    uploaderName TEXT NOT NULL,
    pubDate INTEGER NOT NULL,
    addedDate INTEGER NOT NULL,
    isDownloaded INTEGER NOT NULL,
    fileSize INTEGER NOT NULL,
    playCount INTEGER NOT NULL,
    lastPlayedAt INTEGER NOT NULL,
    PRIMARY KEY(id));

CREATE TABLE IF NOT EXISTS playlists (
    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    coverUrl TEXT,
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    songCount INTEGER NOT NULL,
    biliFavoriteId INTEGER);

CREATE TABLE IF NOT EXISTS playlist_songs (
    playlistId INTEGER NOT NULL,
    songId TEXT NOT NULL,
    position INTEGER NOT NULL,
    addedAt INTEGER NOT NULL,
    PRIMARY KEY(playlistId,
    songId));

CREATE TABLE IF NOT EXISTS downloads (
    songId TEXT NOT NULL,
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    status TEXT NOT NULL,
    progress INTEGER NOT NULL,
    totalBytes INTEGER NOT NULL,
    downloadedBytes INTEGER NOT NULL,
    audioUrl TEXT NOT NULL,
    localPath TEXT,
    errorMessage TEXT,
    startedAt INTEGER NOT NULL,
    completedAt INTEGER,
    PRIMARY KEY(songId));

CREATE TABLE IF NOT EXISTS bili_favorite_folders (
    id INTEGER NOT NULL,
    fid INTEGER NOT NULL,
    mid INTEGER NOT NULL,
    title TEXT NOT NULL,
    cover TEXT,
    mediaCount INTEGER NOT NULL,
    attr INTEGER NOT NULL,
    ctime INTEGER NOT NULL,
    mtime INTEGER NOT NULL,
    cachedAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    PRIMARY KEY(id));

CREATE TABLE IF NOT EXISTS cached_playback_urls (
    bvid TEXT NOT NULL,
    cid INTEGER NOT NULL,
    audioUrl TEXT NOT NULL,
    title TEXT NOT NULL,
    artist TEXT NOT NULL,
    coverUrl TEXT NOT NULL,
    duration INTEGER NOT NULL,
    cachedAt INTEGER NOT NULL,
    expiresAt INTEGER NOT NULL,
    PRIMARY KEY(bvid));

CREATE TABLE IF NOT EXISTS cached_favorite_medias (
    folderId INTEGER NOT NULL,
    id INTEGER NOT NULL,
    type INTEGER NOT NULL,
    title TEXT NOT NULL,
    cover TEXT NOT NULL,
    bvid TEXT NOT NULL,
    upperMid INTEGER NOT NULL,
    upperName TEXT NOT NULL,
    upperFace TEXT NOT NULL,
    duration INTEGER NOT NULL,
    intro TEXT NOT NULL,
    ctime INTEGER NOT NULL,
    pubtime INTEGER NOT NULL,
    position INTEGER NOT NULL,
    cachedAt INTEGER NOT NULL,
    PRIMARY KEY(folderId,
    bvid));

CREATE TABLE IF NOT EXISTS room_master_table (
    id INTEGER PRIMARY KEY,identity_hash TEXT);

INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '110f59e2c8479c1bfb753d71e0e4c468');
