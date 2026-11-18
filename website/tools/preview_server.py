"""Local static preview with byte ranges, so browser videos can seek normally."""

import argparse
import re
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Handler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, ".webp": "image/webp"}

    def send_head(self):
        path = Path(self.translate_path(self.path))
        self.remaining = None
        header = self.headers.get("Range")
        if path.is_file() and header:
            match = re.fullmatch(r"bytes=(\d+)-(\d*)", header)
            if match:
                size = path.stat().st_size
                start = int(match[1])
                end = min(int(match[2]) if match[2] else size - 1, size - 1)
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.end_headers()
                    return None
                stream = path.open("rb")
                stream.seek(start)
                self.remaining = end - start + 1
                self.send_response(206)
                self.send_header("Content-Type", self.guess_type(str(path)))
                self.send_header("Content-Length", str(self.remaining))
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                self.send_header("Accept-Ranges", "bytes")
                self.end_headers()
                return stream
        return super().send_head()

    def copyfile(self, source, output):
        if self.remaining is None:
            return super().copyfile(source, output)
        while self.remaining:
            data = source.read(min(65536, self.remaining))
            if not data:
                break
            output.write(data)
            self.remaining -= len(data)

    def handle(self):
        try:
            super().handle()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=4173)
    parser.add_argument("--directory", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    from functools import partial
    ThreadingHTTPServer(("127.0.0.1", args.port), partial(Handler, directory=str(args.directory))).serve_forever()
