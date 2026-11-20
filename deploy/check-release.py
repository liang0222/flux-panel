"""Verify that the tagged installers select the images and binaries built by CI."""
import hashlib
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]


def require_reference(path, text):
    if text not in (ROOT / path).read_text(encoding="utf-8"):
        raise SystemExit(f"{path}: missing release reference {text!r}")


def digest(path):
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def check(version, assets):
    if not re.fullmatch(r"2\.0\.7-beta-r[1-9][0-9]*", version):
        raise SystemExit(f"Unexpected release tag: {version}")

    for name in ("panel_install.sh", "install.sh"):
        require_reference("README.md", f"/refs/tags/{version}/{name}")
    require_reference(
        "springboot-backend/src/main/java/com/admin/service/impl/NodeServiceImpl.java",
        f"/refs/tags/{version}/install.sh",
    )
    require_reference("install.sh", f'AGENT_RELEASE_TAG="{version}"')
    require_reference("panel_install.sh", f"FLUX_COMPOSE_REF:-refs/tags/{version}" + "}")

    for variant in ("v4", "v6"):
        checksum = digest(ROOT / f"docker-compose-{variant}.yml")
        require_reference("panel_install.sh", f"FLUX_COMPOSE_SHA256_{variant.upper()}:-{checksum}" + "}")
    for path in ("panel_install.sh", "docker-compose-v4.yml", "docker-compose-v6.yml", "deploy/docker-compose.registry.yml"):
        for component in ("backend", "frontend"):
            require_reference(path, f"ahmadplayz721/flux-panel-{component}:{version}" + "}")

    checksums = []
    for arch in ("amd64", "arm64"):
        name = f"gost-{arch}"
        checksum = digest(assets / name)
        require_reference("install.sh", f"{arch}) expected={checksum} ;;")
        checksums.append(f"{checksum}  {name}")
    if (assets / "SHA256SUMS").read_text(encoding="ascii").splitlines() != checksums:
        raise SystemExit("Agent asset checksums do not match SHA256SUMS")
    print(f"{version}: installers, Compose templates and Agent asset digests match")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: check-release.py VERSION AGENT_ASSET_DIRECTORY")
    check(sys.argv[1], Path(sys.argv[2]))
