use anyhow::{Result, ensure};
use bili_music_player::{
    database::Library,
    settings::{Settings, android_values},
};
use rusqlite::Connection;
use std::{fs::File, path::Path};
use tempfile::tempdir;

#[test]
fn installed_android_backup_opens_in_place_with_wal_and_preferences() -> Result<()> {
    let dir = tempdir()?;
    let archive = File::open("../backup/appdata_v5_20260814_183250.tar")?;
    for entry in tar::Archive::new(archive).entries()? {
        ensure!(entry?.unpack_in(dir.path())?);
    }
    let path = dir.path().join("databases/bili_music_database");
    let original = dir.path().join("original.sqlite");
    let before = Connection::open(&path)?;
    before.execute("VACUUM INTO ?1", [original.to_str().unwrap()])?;
    drop(before);
    let library = Library::open(&path)?;
    let db = &library.0;
    db.execute("ATTACH ?1 AS old", [original.to_str().unwrap()])?;
    for table in [
        "songs",
        "downloads",
        "playlists",
        "playlist_songs",
        "bili_favorite_folders",
        "cached_favorite_medias",
        "cached_playback_urls",
        "room_master_table",
    ] {
        for (a, b) in [("main", "old"), ("old", "main")] {
            let same: bool = db.query_row(
                &format!(
                    "SELECT NOT EXISTS (SELECT * FROM {a}.{table} EXCEPT SELECT * FROM {b}.{table})"
                ),
                [],
                |r| r.get(0),
            )?;
            assert!(same, "old rows differ in {table}");
        }
    }
    let folders = library.folders()?;
    assert!(!folders.is_empty());
    assert!(!library.downloaded()?.is_empty());
    let files = dir.path().join("files");
    let values = android_values(&files)?;
    for key in [
        "cookies",
        "access_token",
        "refresh_token",
        "user_id",
        "playback_state",
        "last_folder_id",
    ] {
        assert!(
            values.get(key).is_some_and(|s| !s.is_empty()),
            "missing {key}"
        );
    }
    let path = files.join("settings.properties");
    let mut prefs = Settings::open(path.clone(), || Ok(values.clone()))?;
    prefs.update(|v| {
        v.insert("theme_mode".into(), "DARK".into());
    })?;
    let saved = Settings::open(path, || panic!("existing file should be reused"))?;
    for (key, expected) in &values {
        if key != "theme_mode" {
            assert!(
                saved.get(key) == Some(expected.as_str()),
                "setting {key} changed"
            );
        }
    }
    Ok(())
}

fn legacy_schema(path: &Path, version: u32) -> Result<()> {
    let db = Connection::open(path)?;
    let ddl = include_str!("../sql/room_v6.sql");
    for sql in ddl.split(';').map(str::trim).filter(|s| !s.is_empty()) {
        if sql.contains("cached_favorite_medias")
            || (version < 5 && sql.contains("cached_playback_urls"))
            || (version < 4 && sql.contains("bili_favorite_folders"))
        {
            continue;
        }
        if version == 1 && sql.contains("CREATE TABLE IF NOT EXISTS downloads") {
            db.execute_batch(
                &sql.replace("title TEXT NOT NULL,", "")
                    .replace("artist TEXT NOT NULL,", ""),
            )?;
        } else {
            db.execute_batch(sql)?;
        }
    }
    db.pragma_update(None, "user_version", version)?;
    db.execute_batch("INSERT INTO songs VALUES ('BVtest','音乐','UP主',NULL,30,'','content://media/42',NULL,42,'BVtest',3,4,'UP主',5,6,1,7,8,9);")?;
    if version == 1 {
        db.execute_batch("INSERT INTO downloads(songId,status,progress,totalBytes,downloadedBytes,audioUrl,startedAt)
            VALUES('BVtest','PAUSED',50,200,100,'',1)")?;
    } else {
        db.execute_batch("INSERT INTO downloads(songId,title,artist,status,progress,totalBytes,downloadedBytes,audioUrl,startedAt)
            VALUES('BVtest','音乐','UP主','PAUSED',50,200,100,'',1)")?;
    }
    Ok(())
}

#[test]
fn upgrades_previous_versions_without_discarding_rows() -> Result<()> {
    for version in 1..=5 {
        let dir = tempdir()?;
        let path = dir.path().join("bili_music_database");
        legacy_schema(&path, version)?;
        for _ in 0..2 {
            let lib = Library::open(&path)?;
            let (title, artist, progress): (String, String, i64) =
                lib.0
                    .query_row("SELECT title,artist,progress FROM downloads", [], |r| {
                        Ok((r.get(0)?, r.get(1)?, r.get(2)?))
                    })?;
            assert_eq!(
                (title.as_str(), artist.as_str(), progress),
                ("音乐", "UP主", 50)
            );
            assert_eq!(lib.downloaded()?[0].uri, "content://media/42");
            assert_eq!(
                lib.0
                    .query_row("PRAGMA user_version", [], |r| r.get::<_, u32>(0))?,
                6
            );
        }
    }
    Ok(())
}

#[test]
fn future_database_is_untouched() -> Result<()> {
    let dir = tempdir()?;
    let path = dir.path().join("db");
    Connection::open(&path)?.execute_batch("CREATE TABLE future(data);PRAGMA user_version=7;")?;
    assert!(Library::open(&path).is_err());
    let db = Connection::open(path)?;
    assert_eq!(
        db.query_row("SELECT count(*) FROM sqlite_schema", [], |r| r
            .get::<_, usize>(0))?,
        1
    );
    Ok(())
}
