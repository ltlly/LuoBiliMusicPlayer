//! java.util.Properties.load(InputStream) uses ISO-8859-1 bytes and UTF-16
//! code-unit escapes. A supplementary character occupies two escaped units.
use crate::settings::Values;
use anyhow::{Context, Result, bail};
use std::io::{Read, Write};

fn whitespace(c: u8) -> bool {
    matches!(c, b' ' | b'\t' | 0x0c)
}

pub fn read(mut reader: impl Read) -> Result<Values> {
    let mut bytes = vec![];
    reader.read_to_end(&mut bytes)?;
    let mut result = Values::new();
    let mut logical = Vec::new();
    let mut continuing = false;
    let mut lines = bytes
        .split_inclusive(|b| matches!(b, b'\r' | b'\n'))
        .peekable();
    while let Some(line) = lines.next() {
        let mut line = line;
        if let Some((end, data)) = line.split_last()
            && matches!(end, b'\r' | b'\n')
        {
            if *end == b'\r' && lines.peek() == Some(&&b"\n"[..]) {
                lines.next();
            }
            line = data;
        }
        let start = line
            .iter()
            .position(|b| !whitespace(*b))
            .unwrap_or(line.len());
        line = &line[start..];
        if !continuing && (line.is_empty() || matches!(line[0], b'#' | b'!')) {
            continue;
        }
        logical.extend_from_slice(line);
        continuing = logical.iter().rev().take_while(|b| **b == b'\\').count() % 2 != 0;
        if continuing {
            logical.pop();
            if lines.peek().is_some() {
                continue;
            }
        }
        let mut split = 0;
        while split < logical.len()
            && !whitespace(logical[split])
            && !matches!(logical[split], b':' | b'=')
        {
            if logical[split] == b'\\' {
                split += 1;
            }
            split += 1;
        }
        let key = decode(&logical[..split])?;
        let mut pos = split;
        while logical.get(pos).is_some_and(|b| whitespace(*b)) {
            pos += 1;
        }
        if matches!(logical.get(pos), Some(b'=' | b':')) {
            pos += 1;
        }
        while logical.get(pos).is_some_and(|b| whitespace(*b)) {
            pos += 1;
        }
        result.insert(key, decode(&logical[pos..])?);
        logical.clear();
    }
    Ok(result)
}

fn decode(s: &[u8]) -> Result<String> {
    let mut chars = Vec::with_capacity(s.len());
    let mut bytes = s.iter().copied();
    while let Some(c) = bytes.next() {
        if c != b'\\' {
            chars.push(c as u16);
            continue;
        }
        let escaped = match bytes.next().context("设置转义值不完整")? {
            b't' => 9,
            b'n' => 10,
            b'f' => 12,
            b'r' => 13,
            b'u' => {
                let mut v = 0;
                for _ in 0..4 {
                    let Some(d) = bytes.next().and_then(|b| char::from(b).to_digit(16)) else {
                        bail!("无效的 UTF-16 设置转义")
                    };
                    v = v * 16 + d as u16;
                }
                v
            }
            literal => literal as u16,
        };
        chars.push(escaped);
    }
    Ok(String::from_utf16(&chars)?)
}

pub fn write(mut file: impl Write, values: &Values) -> Result<()> {
    let mut entries = values.iter().collect::<Vec<_>>();
    entries.sort_by_key(|(k, _)| *k);
    for (key, value) in entries {
        encode(&mut file, key, true)?;
        file.write_all(b"=")?;
        encode(&mut file, value, false)?;
        file.write_all(b"\n")?;
    }
    Ok(())
}
fn encode(file: &mut impl Write, s: &str, key: bool) -> Result<()> {
    for (i, c) in s.encode_utf16().enumerate() {
        match c {
            9 => file.write_all(br"\t")?,
            10 => file.write_all(br"\n")?,
            12 => file.write_all(br"\f")?,
            13 => file.write_all(br"\r")?,
            32 if key || i == 0 => file.write_all(br"\ ")?,
            33 | 35 | 58 | 61 | 92 => file.write_all(&[b'\\', c as u8])?,
            32..=126 => file.write_all(&[c as u8])?,
            _ => write!(file, "\\u{c:04X}")?,
        }
    }
    Ok(())
}
