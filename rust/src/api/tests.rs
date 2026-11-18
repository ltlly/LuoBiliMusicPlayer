use super::*;
use crate::{database::Library, playback::Track, settings::Values};
use std::sync::{Arc, Mutex};
use tempfile::{TempDir, tempdir};
use tiny_http::{Response, Server};

struct Fixture {
    _dir: TempDir,
    client: Client,
    library: Library,
    trace: Arc<Mutex<Vec<String>>>,
    server: Arc<Server>,
    worker: Option<thread::JoinHandle<()>>,
}

impl Fixture {
    fn new(responses: Vec<(u16, Value)>) -> Result<Self> {
        let dir = tempdir()?;
        let server = Arc::new(Server::http("127.0.0.1:0").map_err(anyhow::Error::msg)?);
        let base = format!("http://{}", server.server_addr());
        let settings = Settings::open(dir.path().join("prefs"), || Ok(Values::new()))?;
        let mut client = Client::with_endpoints(settings, &base, &base)?;
        client.web = base;
        let library = Library::open(&dir.path().join("db"))?;
        let trace = Arc::new(Mutex::new(vec![]));
        let listener = server.clone();
        let records = trace.clone();
        let worker = thread::spawn(move || {
            let mut responses = responses.into_iter();
            while let Ok(req) = listener.recv() {
                records
                    .lock()
                    .unwrap()
                    .push(req.url().split('?').next().unwrap().to_owned());
                let (status, body) = responses.next().unwrap_or((500, json!({"code":-500})));
                let wire = body
                    .as_str()
                    .map(str::to_owned)
                    .unwrap_or_else(|| body.to_string());
                req.respond(Response::from_string(wire).with_status_code(status))
                    .unwrap();
            }
        });
        Ok(Self {
            _dir: dir,
            client,
            library,
            trace,
            server,
            worker: Some(worker),
        })
    }
    fn trace(&self) -> Vec<String> {
        self.trace.lock().unwrap().clone()
    }
    fn resolve(&mut self) -> Result<String> {
        let mut track = Track {
            id: "BVtest".into(),
            uri: "bili://BVtest".into(),
            title: "旧标题".into(),
            artist: "UP".into(),
            cover: String::new(),
            duration_ms: 0,
        };
        self.client.playback_uri(&self.library, &mut track, true)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        self.server.unblock();
        self.worker.take().unwrap().join().unwrap();
    }
}

fn ok(data: Value) -> (u16, Value) {
    (200, json!({"code":0,"data":data}))
}
fn nav() -> (u16, Value) {
    ok(
        json!({"wbi_img":{"img_url":"https://i/0123456789abcdef0123456789abcdef.png","sub_url":"https://i/fedcba9876543210fedcba9876543210.png"}}),
    )
}
fn audio() -> (u16, Value) {
    ok(
        json!({"dash":{"audio":[{"id":30280,"base_url":"https://cdn/aac","bandwidth":192000}],
    "flac":{"audio":{"id":30251,"baseUrl":"https://cdn/flac","backupUrl":["https://cdn/flac2"]}}}}),
    )
}
const VIEW: &str = "/x/web-interface/view";
const PLAY: &str = "/x/player/playurl";

#[test]
fn cold_then_warm_and_expired_url_requests() -> Result<()> {
    let mut f = Fixture::new(vec![ok(json!({"cid":123})), audio(), audio()])?;
    assert_eq!(f.resolve()?, "https://cdn/flac");
    assert_eq!(f.resolve()?, "https://cdn/flac");
    assert_eq!(f.trace(), [VIEW, PLAY]);
    f.library
        .0
        .execute("UPDATE cached_playback_urls SET expiresAt=0", [])?;
    f.resolve()?;
    assert_eq!(f.trace(), [VIEW, PLAY, PLAY]);
    Ok(())
}

#[test]
fn stale_cid_recovers_once_and_persists() -> Result<()> {
    let mut f = Fixture::new(vec![
        (200, json!({"code":-404})),
        ok(json!({"cid":12})),
        audio(),
    ])?;
    f.library.0.execute(
        "INSERT INTO cached_playback_urls VALUES ('BVtest',11,'https://cdn/old','','','',0,0,0)",
        [],
    )?;
    f.resolve()?;
    f.resolve()?;
    assert_eq!(f.trace(), [PLAY, VIEW, PLAY]);
    assert_eq!(
        f.library
            .0
            .query_row("SELECT cid FROM cached_playback_urls", [], |r| r
                .get::<_, i64>(0))?,
        12
    );
    Ok(())
}

#[test]
fn wbi_fallback_reuses_initialized_keys() -> Result<()> {
    let mut f = Fixture::new(vec![
        (200, json!({"code":-403})),
        nav(),
        audio(),
        (200, json!({"code":-403})),
        audio(),
    ])?;
    f.client.resolve_audio("BVtest", Some(11), true)?;
    f.client.resolve_audio("BVtest", Some(11), true)?;
    assert_eq!(
        f.trace(),
        [
            PLAY,
            NAV,
            "/x/player/wbi/playurl",
            PLAY,
            "/x/player/wbi/playurl"
        ]
    );
    Ok(())
}

#[test]
fn cooldown_survives_restart_and_suppresses_fallback() -> Result<()> {
    let mut f = Fixture::new(vec![(412, Value::Null)])?;
    assert!(f.client.resolve_audio("BVtest", Some(11), true).is_err());
    let prefs = Settings::open(f._dir.path().join("prefs"), || unreachable!())?;
    let mut reopened = Client::with_endpoints(prefs, &f.client.api, &f.client.passport)?;
    assert!(reopened.resolve_audio("BVtest", Some(11), true).is_err());
    assert_eq!(f.trace(), [PLAY]);
    Ok(())
}

#[test]
fn qr_login_and_device_cookie_are_reused() -> Result<()> {
    let mut f = Fixture::new(vec![
        ok(json!({"b_3":"3","b_4":"4"})),
        ok(json!({"url":"https://test/qr","auth_code":"code"})),
        (200, json!({"code":86039})),
        ok(
            json!({"access_token":"t","refresh_token":"r","cookie_info":{"cookies":[{"name":"SESSDATA","value":"test"}]}}),
        ),
        ok(json!({"isLogin":true,"mid":42,"uname":"测试"})),
    ])?;
    f.client.ensure_buvid()?;
    f.client.ensure_buvid()?;
    let (_, code) = f.client.start_qr()?;
    assert!(!f.client.poll_qr(&code)?);
    assert!(f.client.poll_qr(&code)?);
    f.client.user_info()?;
    assert!(f.client.logged_in());
    assert_eq!(f.client.settings.get("user_id"), Some("42"));
    assert_eq!(
        f.trace(),
        [
            "/x/frontend/finger/spi",
            "/x/passport-tv-login/qrcode/auth_code",
            "/x/passport-tv-login/qrcode/poll",
            "/x/passport-tv-login/qrcode/poll",
            NAV
        ]
    );
    Ok(())
}

#[test]
fn account_check_uses_one_nav_and_logout_preserves_library_preferences() -> Result<()> {
    let mut f = Fixture::new(vec![ok(
        json!({"isLogin":true,"mid":32,"uname":"小明","vipStatus":1}),
    )])?;
    f.client.settings.update(|values| {
        for (key, value) in [
            ("theme_mode", "DARK"),
            ("playback_state", "queue"),
            ("access_token", "test"),
            ("refresh_token", "test"),
            ("user_id", "32"),
            ("cookies", "{}"),
        ] {
            values.insert(key.into(), value.into());
        }
    })?;
    assert!(f.client.logged_in());
    assert!(f.client.account_status()?.contains("UID：32\n大会员有效"));
    f.client.logout()?;
    assert!(!f.client.logged_in());
    assert!(f.client.settings.get("cookies").is_none());
    assert_eq!(f.client.settings.get("theme_mode"), Some("DARK"));
    assert_eq!(f.client.settings.get("playback_state"), Some("queue"));
    assert_eq!(f.trace(), [NAV]);
    Ok(())
}

#[test]
fn folder_completion_batches_missing_metadata_without_resolving_audio() -> Result<()> {
    let ids: Vec<_> = (0..23)
        .map(|i| json!({"id":i,"type":2,"bv_id":format!("BV{i}")}))
        .collect();
    let mut f = Fixture::new(vec![
        ok(json!(ids)),
        ok(json!([])),
        ok(json!([])),
        ok(json!(ids)),
    ])?;
    let cached = Media {
        id: 0,
        kind: 2,
        bvid: "BV0".into(),
        title: "缓存".into(),
        ..Default::default()
    };
    f.library.store_media(5, &[cached], 0, now_ms())?;
    for _ in 0..2 {
        let mut job = crate::folder_queue::FolderQueue::new(5, 23, "BV19".into());
        let mut tracks = Vec::new();
        while !job.step(&mut f.client, &mut f.library, &mut tracks)? {}
        assert_eq!(tracks.len(), 23);
        assert_eq!(tracks[0].title, "缓存");
        assert_eq!(tracks[22].title, "已失效视频");
    }
    let ids = "/x/v3/fav/resource/ids";
    let infos = "/x/v3/fav/resource/infos";
    assert_eq!(f.trace(), [ids, infos, infos, ids]);
    Ok(())
}

#[cfg(not(target_os = "android"))]
#[test]
fn batch_download_resolves_once_and_publishes_original_bytes() -> Result<()> {
    use crate::download::{Downloads, enqueue};
    let cdn = Server::http("127.0.0.1:0").map_err(anyhow::Error::msg)?;
    let url = format!("http://{}/audio", cdn.server_addr());
    let mut f = Fixture::new(vec![
        ok(json!({"cid":12})),
        ok(json!({"dash":{"audio":[{"id":30280,"baseUrl":url}]}})),
    ])?;
    let music = f._dir.path().join("Music");
    std::fs::create_dir(&music)?;
    std::fs::write(music.join("测试.m4a"), b"existing")?;
    f.client.settings.update(|v| {
        v.insert("download_dir".into(), music.to_string_lossy().into_owned());
    })?;
    let list = [Media {
        bvid: "BVtest".into(),
        id: 1,
        title: "测试".into(),
        ..Default::default()
    }];
    let mut downloads = Downloads::open(&f.library, f._dir.path().join("db"))?;
    assert_eq!(enqueue(&mut f.library, &list)?, 1);
    assert_eq!(enqueue(&mut f.library, &list)?, 0);
    downloads.wake();
    downloads.tick(&mut f.client, &mut f.library)?;
    let request = cdn
        .recv_timeout(Duration::from_secs(3))?
        .context("missing CDN request")?;
    assert!(!request.headers().iter().any(|h| h.field.equiv("Cookie")));
    request.respond(Response::from_data(b"original source bytes".to_vec()))?;
    let deadline = std::time::Instant::now() + Duration::from_secs(5);
    while downloads.pending && std::time::Instant::now() < deadline {
        thread::sleep(Duration::from_millis(25));
        downloads.tick(&mut f.client, &mut f.library)?;
    }
    let tracks = f.library.downloaded()?;
    assert_eq!(tracks.len(), 1);
    assert_eq!(std::fs::read(&tracks[0].uri)?, b"original source bytes");
    assert_eq!(std::fs::read(music.join("测试.m4a"))?, b"existing");
    assert_eq!(enqueue(&mut f.library, &list)?, 0);
    assert_eq!(f.trace(), [VIEW, PLAY]);
    assert_eq!(crate::download::rows(&f.library, 1, "")?.len(), 1);
    Ok(())
}

#[test]
fn busy_api_reads_only_one_public_page_and_reuses_its_audio() -> Result<()> {
    let id = "BV1Hf4y1L7MF";
    let metadata = json!({"videoData":{"bvid":id,"cid":42}});
    let html = format!(
        "<script>window.__INITIAL_STATE__ = {metadata};(function(){{}})()</script><script>window.__playinfo__={}</script>",
        audio().1
    );
    let mut f = Fixture::new(vec![(503, Value::Null), (200, Value::String(html))])?;
    let result = f.client.resolve_audio(id, None, true)?;
    assert_eq!((result.0, result.1.format_id), (42, 30251));
    assert_eq!(f.trace(), [VIEW, "/video/BV1Hf4y1L7MF/"]);
    Ok(())
}

#[test]
fn metadata_only_page_uses_playurl_once_and_rejects_a_wrong_video() -> Result<()> {
    let id = "BV1Hf4y1L7MF";
    let page = json!(
        "<script>window.__INITIAL_STATE__={\"videoData\":{\"bvid\":\"BV1Hf4y1L7MF\",\"cid\":42}};</script>"
    );
    let mut f = Fixture::new(vec![
        (502, Value::Null),
        (200, page.clone()),
        audio(),
        (200, page),
    ])?;
    assert_eq!(f.client.resolve_audio(id, None, true)?.0, 42);
    assert!(f.client.video_page("BV1xxxxxxxxx").is_err());
    assert_eq!(
        f.trace(),
        [VIEW, "/video/BV1Hf4y1L7MF/", PLAY, "/video/BV1xxxxxxxxx/"]
    );
    Ok(())
}

#[test]
fn rate_limit_never_triggers_web_or_login_requests() -> Result<()> {
    for (status, body) in [(429, Value::Null), (200, json!({"code":-412}))] {
        let mut f = Fixture::new(vec![(status, body)])?;
        assert!(f.client.resolve_audio("BV1Hf4y1L7MF", None, true).is_err());
        assert!(f.client.video_page("BV1Hf4y1L7MF").is_err());
        assert!(f.client.start_qr().is_err());
        assert_eq!(f.trace(), [VIEW]);
    }
    let mut f = Fixture::new(vec![])?;
    assert!(f.client.check_limit(503, None, Some("1800")).is_err());
    assert!(f.client.number("gate_cooldown", 0) >= now_ms() + 1_799_000);
    Ok(())
}

#[test]
fn warm_folder_reopens_without_traffic_and_refresh_fetches_once() -> Result<()> {
    let page = ok(json!({"medias":[],"info":{"media_count":0}}));
    let mut f = Fixture::new(vec![page.clone(), page.clone(), page])?;
    for _ in 0..3 {
        f.client.folder_page(7, 1, None)?;
    }
    f.client.folder_page(7, 1, Some("春"))?;
    f.client.folder_page(7, 1, Some("春"))?;
    f.client.refresh_folder(7);
    f.client.folder_page(7, 1, None)?;
    assert_eq!(f.trace(), ["/x/v3/fav/resource/list"; 3]);
    Ok(())
}

#[test]
fn expired_cdn_deadline_overrides_legacy_six_hour_ttl() -> Result<()> {
    let mut f = Fixture::new(vec![audio()])?;
    f.library.0.execute("INSERT INTO cached_playback_urls VALUES ('BVtest',11,'https://cdn/aac?deadline=1000','','','',0,0,?1)", [now_ms()+21_600_000])?;
    f.resolve()?;
    f.resolve()?;
    assert_eq!(f.trace(), [PLAY]);
    Ok(())
}

#[test]
fn public_folder_summaries_can_omit_dates_and_cover() -> Result<()> {
    let data = json!({"list":[{"id":123,"fid":12,"mid":3,"title":"音乐","media_count":5,"attr":0,"fav_state":0}]});
    let mut f = Fixture::new(vec![ok(data)])?;
    let folders = f.client.folders(3)?;
    f.library.store_folders(&folders, now_ms())?;
    let saved = f.library.folders()?;
    assert_eq!(
        (
            saved[0].title.as_str(),
            saved[0].media_count,
            saved[0].mtime
        ),
        ("音乐", 5, 0)
    );
    assert_eq!(f.trace(), ["/x/v3/fav/folder/created/list-all"]);
    Ok(())
}

#[test]
fn paused_downloads_stay_in_the_active_tab_to_resume() -> Result<()> {
    let f = Fixture::new(vec![])?;
    f.library.0.execute("INSERT INTO downloads(songId,title,artist,status,progress,totalBytes,downloadedBytes,audioUrl,startedAt) VALUES('test','测试','歌手','PAUSED',0,0,0,'',0)",[])?;
    assert_eq!(crate::download::rows(&f.library, 0, "")?[0].3, "PAUSED");
    assert!(crate::download::rows(&f.library, 2, "")?.is_empty());
    Ok(())
}
