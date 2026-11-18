//! Stage the CDN response before asking Android for a writable media descriptor.
use anyhow::{Context, Result, ensure};
use std::{
    fs::File,
    io::{Read, Write},
    path::Path,
    time::Duration,
};

pub(crate) fn fetch(
    urls: &[String],
    original: &Path,
    stage: &Path,
    mut stopped: impl FnMut() -> bool,
) -> Result<bool> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_connect(Some(Duration::from_secs(30)))
        .timeout_recv_body(Some(Duration::from_secs(60)))
        .build()
        .into();
    let mut error = anyhow::anyhow!("音频地址为空");
    for url in urls {
        ensure!(!stopped(), "升级已暂停");
        let result = (|| {
            let mut response = agent
                .get(url)
                .header("Referer", "https://www.bilibili.com/")
                .header("User-Agent", "Mozilla/5.0")
                .call()?;
            let length = response
                .headers()
                .get("Content-Length")
                .and_then(|v| v.to_str().ok())
                .and_then(|v| v.parse::<u64>().ok())
                .filter(|&n| n > 0);
            let mut body = response.body_mut().as_reader();
            let mut prefix = Vec::with_capacity(65536);
            (&mut body).take(65536).read_to_end(&mut prefix)?;
            ensure!(!prefix.is_empty(), "音频响应为空");
            let mut old = File::open(original)?;
            if length == Some(old.metadata()?.len()) {
                let mut local = vec![0; prefix.len()];
                old.read_exact(&mut local)?;
                if local == prefix {
                    return Ok(true);
                }
            }
            let mut file = File::create(stage)?;
            file.write_all(&prefix)?;
            let mut count = prefix.len() as u64;
            let mut buffer = [0; 32768];
            loop {
                ensure!(!stopped(), "升级已暂停");
                let n = body.read(&mut buffer)?;
                if n == 0 {
                    break;
                }
                file.write_all(&buffer[..n])?;
                count += n as u64;
            }
            ensure!(length.is_none_or(|n| n == count), "音频下载不完整");
            file.sync_all()?;
            Ok(false)
        })();
        match result {
            Ok(same) => return Ok(same),
            Err(e) => error = e,
        }
    }
    Err(error).context("下载音轨失败")
}

#[cfg(test)]
mod tests {
    use super::*;
    use tiny_http::{Response, Server};
    #[test]
    fn identical_prefix_uses_one_get_and_different_audio_uses_ordered_fallback() {
        let dir = tempfile::tempdir().unwrap();
        let old = dir.path().join("old");
        let stage = dir.path().join("stage");
        let first = vec![4u8; 100000];
        std::fs::write(&old, &first).unwrap();
        let server = Server::http("127.0.0.1:0").unwrap();
        let endpoint = format!("http://{}", server.server_addr());
        let worker = std::thread::spawn(move || {
            for name in ["/same", "/missing", "/audio"] {
                let request = server
                    .recv_timeout(Duration::from_secs(3))
                    .unwrap()
                    .unwrap();
                assert_eq!(request.url(), name);
                assert_eq!(request.method().as_str(), "GET");
                let reply = match name {
                    "/same" => Response::from_data(first.clone()),
                    "/missing" => Response::from_data(Vec::new()).with_status_code(404),
                    _ => Response::from_data(vec![9; 70000]),
                };
                let _ = request.respond(reply.with_chunked_threshold(1048576));
            }
        });
        assert!(fetch(&[format!("{endpoint}/same")], &old, &stage, || false).unwrap());
        assert!(!stage.exists());
        assert!(
            !fetch(
                &[format!("{endpoint}/missing"), format!("{endpoint}/audio")],
                &old,
                &stage,
                || false
            )
            .unwrap()
        );
        assert_eq!(std::fs::read(stage).unwrap(), vec![9; 70000]);
        assert_eq!(std::fs::read(old).unwrap(), vec![4; 100000]);
        worker.join().unwrap();
    }
}
