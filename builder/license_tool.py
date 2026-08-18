#!/usr/bin/env python3
"""
离线买断授权签发工具（Android）。

依赖：
  pip install cryptography

用法：
  1) 生成密钥对
     python builder/license_tool.py gen-keypair --out-dir builder/license_keys

  2) 签发授权
     python builder/license_tool.py sign \
       --private-key builder/license_keys/private_key.pem \
       --device-code <设备码> \
       --customer "客户名" \
       --out license.json
"""

from __future__ import annotations

import argparse
import base64
import json
from datetime import datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

APP_ID = "com.pupu.taojinbi"


def gen_keypair(out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_key = private_key.public_key()

    private_pem = private_key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    )
    public_pem = public_key.public_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_der = public_key.public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )

    (out_dir / "private_key.pem").write_bytes(private_pem)
    (out_dir / "public_key.pem").write_bytes(public_pem)
    (out_dir / "public_key.b64.txt").write_text(
        base64.b64encode(public_der).decode("ascii"),
        encoding="utf-8",
    )
    print(f"已生成密钥对到: {out_dir}")
    print("把 public_key.b64.txt 内容粘贴到 LicenseManager.PUBLIC_KEY_B64")


def sign_license(private_key_path: Path, device_code: str, customer: str, out_file: Path) -> None:
    private_key = serialization.load_pem_private_key(private_key_path.read_bytes(), password=None)

    payload_obj = {
        "app_id": APP_ID,
        "device_code": device_code,
        "license_type": "perpetual",
        "customer": customer,
        "issued_at": datetime.now(timezone.utc).isoformat(),
    }
    payload = json.dumps(payload_obj, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    signature = private_key.sign(
        payload.encode("utf-8"),
        padding.PKCS1v15(),
        hashes.SHA256(),
    )
    lic_obj = {
        "payload": payload,
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    out_file.write_text(json.dumps(lic_obj, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"已签发授权: {out_file}")


def main() -> None:
    parser = argparse.ArgumentParser(description="离线买断授权签发工具")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_gen = sub.add_parser("gen-keypair", help="生成 RSA 密钥对")
    p_gen.add_argument("--out-dir", type=Path, required=True, help="输出目录")

    p_sign = sub.add_parser("sign", help="签发授权文件")
    p_sign.add_argument("--private-key", type=Path, required=True, help="私钥 PEM 路径")
    p_sign.add_argument("--device-code", required=True, help="设备码（App 内复制）")
    p_sign.add_argument("--customer", required=True, help="客户名称")
    p_sign.add_argument("--out", type=Path, required=True, help="授权输出文件")

    args = parser.parse_args()
    if args.cmd == "gen-keypair":
        gen_keypair(args.out_dir)
    elif args.cmd == "sign":
        sign_license(args.private_key, args.device_code, args.customer, args.out)


if __name__ == "__main__":
    main()
