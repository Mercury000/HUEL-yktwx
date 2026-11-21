"""Reject scanner profiles that omit Activity entry, preview binding or QR analysis."""

import argparse
from pathlib import Path

APPLICATION = "Lcom/mercury/yktwx/"
REQUIRED_METHODS = (
    APPLICATION + "ScannerActivity;->onCreate(",
    APPLICATION + "ui/components/DeviceScannerKt;->DeviceScanner(",
    APPLICATION + "ui/components/CameraXDeviceCameraBackend;->bind(",
    APPLICATION + "ui/components/WeChatDeviceQrDecoder;->decode(",
    APPLICATION + "ui/components/WeChatDeviceQrDecoder;->prepare(",
)


def verify(path: Path) -> None:
    rules = {line.strip().lstrip("HSP") for line in path.read_text(encoding="utf-8").splitlines()
             if line.strip() and not line.lstrip().startswith("#")}
    missing = [method for method in REQUIRED_METHODS if not any(rule.startswith(method) for rule in rules)]
    if missing:
        raise ValueError("Scanner methods missing from collected profile: " + ", ".join(missing))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    verify(parser.parse_args().profile)
    print("Scanner Activity, Compose UI, CameraX binding and QR analysis coverage verified")
