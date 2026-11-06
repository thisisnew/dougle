"""Bounded Slack-hosted image input. No disk cache, arbitrary URL fetch or OCR service."""

import base64
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from urllib.parse import urlsplit

from .safety import BotError

MAX_IMAGE_BYTES = 5 * 1024 * 1024
MAX_TOTAL_BYTES = 12 * 1024 * 1024
MAX_IMAGES = 4


@dataclass(frozen=True, repr=False)
class ImageInput:
    file_id: str
    message_ts: str
    media_type: str
    data: bytes

    def blocks(self):
        return [
            {
                "type": "text",
                "text": f"Slack image {self.file_id}, message timestamp {self.message_ts}",
            },
            {
                "type": "image",
                "source": {
                    "type": "base64",
                    "media_type": self.media_type,
                    "data": base64.b64encode(self.data).decode("ascii"),
                },
            },
        ]


def image_files(messages):
    result = {}
    for message in messages:
        if message.get("bot_id"):
            continue
        files = message.get("files", [])
        if not isinstance(files, list):
            raise BotError("image_metadata_invalid")
        for file in files:
            if not isinstance(file, dict):
                raise BotError("image_metadata_invalid")
            # Missing-type stubs may be permission-hidden images. Do not silently ignore them.
            mime = file.get("mimetype", "")
            if mime and not str(mime).startswith("image/"):
                continue
            ident = file.get("id", "")
            if not isinstance(ident, str) or not re.fullmatch(r"F[A-Z0-9]{2,30}", ident):
                raise BotError("image_metadata_invalid")
            result.setdefault(ident, (file, message["ts"]))
        # Remote/Block Kit-only images are not Slack uploaded files and lack a safe download path.
        blocks = message.get("blocks", [])
        if not isinstance(blocks, list):
            raise BotError("image_metadata_invalid")
        for block in blocks:
            if isinstance(block, dict) and block.get("type") == "image":
                slack_file = block.get("slack_file", {})
                if not isinstance(slack_file, dict) or slack_file.get("id") not in result:
                    raise BotError("image_attachment_unsupported")
    if len(result) > MAX_IMAGES:
        raise BotError("too_many_images")
    return list(result.values())


def validate_image(data, mime):
    if mime not in ("image/png", "image/jpeg"):
        raise BotError("image_type_unsupported")
    if not isinstance(data, bytes) or not data or len(data) > MAX_IMAGE_BYTES:
        raise BotError("image_size_limit")
    if mime == "image/png":
        valid = (
            len(data) >= 33
            and data[:8] == b"\x89PNG\r\n\x1a\n"
            and data[12:16] == b"IHDR"
            and data[-8:-4] == b"IEND"
        )
        if valid:
            width, height = int.from_bytes(data[16:20]), int.from_bytes(data[20:24])
            valid = 0 < width <= 8000 and 0 < height <= 8000
    else:
        valid = len(data) > 4 and data[:3] == b"\xff\xd8\xff" and data[-2:] == b"\xff\xd9"
    if not valid:
        raise BotError("image_data_invalid")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        # Never forward a Slack bearer token to a URL supplied by another host.
        return None


def download_image(url, token, *, opener=None):
    try:
        parsed = urlsplit(url)
        valid = (
            parsed.scheme == "https"
            and parsed.netloc == "files.slack.com"
            and parsed.path.startswith("/files-pri/")
            and not parsed.fragment
            and not any(ord(c) < 33 for c in url)
        )
    except (ValueError, TypeError):
        valid = False
    if not valid:
        raise BotError("image_download_url_rejected")
    request = urllib.request.Request(url, headers={"Authorization": "Bearer " + token})
    opener = opener or urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=20) as response:
            if response.status != 200:
                raise BotError("image_download_failed")
            size = response.headers.get("Content-Length")
            if size is not None and (not size.isdigit() or int(size) > MAX_IMAGE_BYTES):
                raise BotError("image_size_limit")
            deadline = time.monotonic() + 20
            data = bytearray()
            while True:
                if time.monotonic() > deadline:
                    raise BotError("image_download_failed")
                chunk = response.read(min(65536, MAX_IMAGE_BYTES + 1 - len(data)))
                if not chunk:
                    break
                data.extend(chunk)
                if len(data) > MAX_IMAGE_BYTES:
                    raise BotError("image_size_limit")
            return bytes(data), response.headers.get("Content-Type", "").split(";", 1)[0].lower()
    except BotError:
        raise
    except urllib.error.HTTPError as exc:
        code = "image_access_denied" if exc.code in (401, 403, 404) else "image_download_failed"
        raise BotError(code) from None
    except Exception:
        raise BotError("image_download_failed") from None


def collect_images(slack, messages):
    images = []
    total = 0
    for metadata, ts in image_files(messages):
        data, mime = slack.image(metadata["id"])
        validate_image(data, mime)
        total += len(data)
        if total > MAX_TOTAL_BYTES:
            raise BotError("image_total_size_limit")
        images.append(ImageInput(metadata["id"], ts, mime, data))
    return images


def prompt_messages(messages):
    # Do not send private download URLs, arbitrary file metadata or binary data as text.
    return [
        {k: row[k] for k in ("ts", "user", "text", "bot_id", "edited") if k in row}
        for row in messages
    ]


IMAGE_ERRORS = {
    "image_access_denied": "이미지를 읽을 권한이 없습니다. Slack 앱에 files:read 권한을 추가하고 워크스페이스에 다시 설치한 뒤, 이미지와 질문을 다시 보내 주세요.",
    "image_download_failed": "이미지를 내려받지 못했습니다. 파일 접근과 연결 상태를 확인한 뒤 이미지와 질문을 다시 보내 주세요.",
    "image_download_url_rejected": "이 이미지의 다운로드 위치는 지원하지 않습니다. PNG 또는 JPEG 파일을 Slack에 직접 첨부해 주세요.",
    "image_attachment_unsupported": "외부 이미지나 미리보기는 읽을 수 없습니다. PNG 또는 JPEG 파일을 Slack에 직접 첨부해 주세요.",
    "image_type_unsupported": "PNG와 JPEG 이미지만 지원합니다. 지원하는 형식으로 첨부해 주세요.",
    "image_metadata_invalid": "첨부 이미지 정보를 확인하지 못했습니다. 이미지와 질문을 다시 보내 주세요.",
    "image_data_invalid": "이미지 형식을 확인하지 못했거나 크기가 지원 범위를 벗어났습니다. PNG 또는 JPEG 캡처로 다시 첨부해 주세요.",
    "image_size_limit": "이미지는 한 장당 5 MiB까지 지원합니다. 필요한 영역을 캡처해서 다시 첨부해 주세요.",
    "image_total_size_limit": "스레드 이미지의 합계가 12 MiB를 넘습니다. 필요한 이미지만 새 스레드에 첨부해 주세요.",
    "too_many_images": "스레드에서 최대 4장의 이미지를 확인할 수 있습니다. 필요한 이미지만 새 스레드에 첨부해 주세요.",
    "image_input_failed": "AI가 첨부 이미지를 처리하지 못했습니다. 이미지 형식과 Claude 실행 환경을 확인해 주세요.",
}
