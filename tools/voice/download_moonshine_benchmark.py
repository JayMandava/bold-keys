"""Download/verify the public manifest emitted by the official SDK (no audio)."""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import json
import logging
import struct
import urllib.request
from pathlib import Path

logger = logging.getLogger(__name__)


def crc_table() -> list[int]:
    table = []
    for value in range(256):
        for _ in range(8):
            value = (value >> 1) ^ (0x82F63B78 if value & 1 else 0)
        table.append(value)
    return table


def download(entry: dict[str, str | int], output: Path) -> None:
    name, url = str(entry["name"]), str(entry["url"])
    if Path(name).name != name or not url.startswith("https://download.moonshine.ai/model/"):
        raise ValueError("Unexpected public model URL or filename")
    target, temporary = output / name, output / f"{name}.part"
    table, crc, size = crc_table(), 0xFFFFFFFF, 0
    request = urllib.request.Request(url, headers={"User-Agent": "BoldKeys-offline-benchmark/1.0"})
    with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as stream:
        while chunk := response.read(65536):
            stream.write(chunk)
            size += len(chunk)
            for byte in chunk:
                crc = table[(crc ^ byte) & 255] ^ (crc >> 8)
    checksum = base64.b64encode(struct.pack(">I", crc ^ 0xFFFFFFFF)).decode()
    if size != entry["size"] or entry["checksum_type"] != "crc32c" or checksum != entry["checksum"]:
        raise ValueError(f"Model verification failed: {name}")
    temporary.replace(target)
    logger.info("Verified %s (%s bytes)", name, size)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(args.manifest.read_text())
    if len(manifest["groups"]) != 1:
        raise ValueError("Expected one speech-model group")
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(download, entry, args.output) for entry in manifest["groups"][0]["files"]]
        for future in futures:
            future.result()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    main()
