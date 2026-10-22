use base64::Engine;
use std::io::Read;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    if arguments.len() != 4 {
        return Err("Expected config, package, signature and version".into());
    }
    let config: serde_json::Value = serde_json::from_slice(&std::fs::read(&arguments[0])?)?;
    let encoded = config["plugins"]["updater"]["pubkey"]
        .as_str()
        .ok_or("Missing public key")?;
    let public = String::from_utf8(base64::engine::general_purpose::STANDARD.decode(encoded)?)?;
    let public = minisign_verify::PublicKey::decode(&public)?;
    let encoded_signature = std::fs::read_to_string(&arguments[2])?;
    let signature = String::from_utf8(
        base64::engine::general_purpose::STANDARD.decode(encoded_signature.trim())?,
    )?;
    let signature = minisign_verify::Signature::decode(&signature)?;
    let mut verifier = public.verify_stream(&signature)?;
    let mut package = std::fs::File::open(&arguments[1])?;
    let mut buffer = [0u8; 65536];
    loop {
        let count = package.read(&mut buffer)?;
        if count == 0 {
            break;
        }
        verifier.update(&buffer[..count]);
    }
    verifier.finalize()?;
    if !signature
        .trusted_comment()
        .split('\t')
        .any(|field| field == format!("version:{}", arguments[3]))
    {
        return Err("Signed version does not match package version".into());
    }
    println!("PASS: update package signature and signed version");
    Ok(())
}
