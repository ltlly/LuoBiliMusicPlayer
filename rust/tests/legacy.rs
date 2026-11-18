use anyhow::{Result, ensure};
use bili_music_player::{
    database::Library,
    settings::{Settings, android_values},
};
use rusqlite::Connection;
use std::{collections::BTreeMap, fs, path::Path};
use tempfile::tempdir;

// AndroidX DataStore maps each preference name to a message whose field 5 is a string.
#[derive(prost::Message)]
struct TextPreferences {
    #[prost(btree_map = "string, message", tag = "1")]
    entries: BTreeMap<String, TextPreference>,
}

#[derive(PartialEq, prost::Message)]
struct TextPreference {
    #[prost(string, tag = "5")]
    text: String,
}

#[test]
fn android_library_opens_in_place_with_uncheckpointed_rows_and_preferences() -> Result<()> {
    use prost::Message;
    let source = tempdir()?;
    let original = source.path().join("db");
    legacy_schema(&original, 6)?;
    let writer = Connection::open(&original)?;
    writer.execute_batch("PRAGMA journal_mode=WAL; PRAGMA wal_autocheckpoint=0;
        BEGIN;
        INSERT INTO bili_favorite_folders VALUES(1,1,42,'演示收藏夹',NULL,1,0,1,1,1,1);
        INSERT INTO cached_favorite_medias VALUES(1,2,2,'音乐','','BVtest',42,'UP主','',30,'',1,1,0,1);
        INSERT INTO cached_playback_urls VALUES('BVtest',42,'https://example.invalid/audio','音乐','UP主','',30,1,2);
        INSERT INTO playlists VALUES(1,'歌单',NULL,NULL,1,1,1,1);
        INSERT INTO playlist_songs VALUES(1,'BVtest',0,1);
        COMMIT;")?;

    // Copy the idle writer's on-disk state before closing it would checkpoint the WAL.
    let dir = tempdir()?;
    let db_dir = dir.path().join("databases");
    fs::create_dir(&db_dir)?;
    fs::copy(original, db_dir.join("bili_music_database"))?;
    fs::copy(
        source.path().join("db-wal"),
        db_dir.join("bili_music_database-wal"),
    )?;
    drop(writer);

    let files = dir.path().join("files");
    fs::create_dir_all(files.join("datastore"))?;
    let queue = r#"[{"id":"BVtest","title":"音乐"}]"#;
    let prefs = TextPreferences {
        entries: [
            ("queue_json", queue),
            ("user_id", "42"),
            ("last_folder_id", "1"),
        ]
        .into_iter()
        .map(|(key, value)| (key.into(), TextPreference { text: value.into() }))
        .collect(),
    };
    fs::write(
        files.join("datastore/app_prefs.preferences_pb"),
        prefs.encode_to_vec(),
    )?;
    let shared = dir.path().join("shared_prefs");
    fs::create_dir(&shared)?;
    fs::write(
        shared.join("cookies.xml"),
        r#"<map><string name="example.invalid">{"demo":"fixture"}</string></map>"#,
    )?;
    let values = android_values(&files)?;
    assert_eq!(values["playback_state"], format!("{queue}\n0\n0\n2\nfalse"));
    assert_eq!(
        values["cookies"],
        r#"{"example.invalid":{"demo":"fixture"}}"#
    );
    verify_android_backup(dir.path())
}

#[test]
#[ignore = "set BILI_TEST_ANDROID_BACKUP to a local Android backup tar"]
fn installed_android_backup_opens_in_place_with_wal_and_preferences() -> Result<()> {
    let dir = tempdir()?;
    let archive = fs::File::open(std::env::var("BILI_TEST_ANDROID_BACKUP")?)?;
    for entry in tar::Archive::new(archive).entries()? {
        ensure!(entry?.unpack_in(dir.path())?);
    }
    verify_android_backup(dir.path())
}

fn verify_android_backup(dir: &Path) -> Result<()> {
    let path = dir.join("databases/bili_music_database");
    let original = dir.join("original.sqlite");
    let before = Connection::open(&path)?;
    before.execute("VACUUM INTO ?1", [original.to_str().unwrap()])?;
    let library = Library::open(&path)?;
    drop(before);
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
    let files = dir.join("files");
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
        if (version < 6 && sql.contains("cached_favorite_medias"))
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
