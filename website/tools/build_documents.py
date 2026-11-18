"""Render existing project documents into the website, without duplicating their text."""
from html import escape
from html.parser import HTMLParser
from pathlib import Path
import json
import re
import shutil
from urllib.parse import unquote, urlparse

from markdown_it import MarkdownIt


DOCUMENTS = {
    "docs/analytics.md": "analytics.html",
    "docs/life-services.md": "life-services.html",
    "docs/development.md": "development.html",
    "docs/architecture.md": "architecture.html",
    "docs/api.md": "api.html",
    "docs/app-updates.md": "app-updates.html",
    "docs/releases.md": "releases.html",
    "docs/performance-profiles.md": "performance-profiles.html",
    "updates/README.md": "update-manifests.html",
    "app/libs/README.md": "libraries.html",
    "LICENSE": "license.html",
    "NOTICE": "notices.html",
}


def project_link(url, source, repository):
    """Keep existing document links local; other project links retain their origin."""
    parsed = urlparse(url)
    if parsed.scheme or parsed.netloc:
        prefix = f"https://github.com/{repository}/blob/main/"
        if not url.startswith(prefix):
            return url
        relative = unquote(url[len(prefix):].split("#", 1)[0])
    elif not parsed.path:
        return url
    else:
        relative = (source.parent / unquote(parsed.path)).as_posix()
        # Resolve '..' lexically without accessing unrelated files.
        parts = []
        for part in relative.split("/"):
            if part == "..":
                if not parts:
                    raise ValueError("Document link escapes the project")
                parts.pop()
            elif part not in ("", "."):
                parts.append(part)
        relative = "/".join(parts)
    fragment = f"#{parsed.fragment}" if parsed.fragment else ""
    if relative in DOCUMENTS:
        return DOCUMENTS[relative] + fragment
    return f"https://github.com/{repository}/blob/main/{relative}{fragment}"


def build_documents(project, website, output, repository):
    (output / "document-links.js").write_text(
        "window.projectDocuments = Object.freeze(" + json.dumps(
            {"repository": repository, "pages": DOCUMENTS}, ensure_ascii=False) + ");\n", encoding="utf-8")
    home = (website / "index.html").read_text(encoding="utf-8")
    navigation = re.search(r'<svg class="svg-library".*?</svg>', home, re.S)[0]
    header = re.search(r'<header class="site-header".*?</header>', home, re.S)[0]
    header = re.sub(r'(<a\b[^>]*?\bhref=")#([^"]*)"', r'\1index.html#\2"', header)
    navigation += header
    footer = re.search(r'<footer class="footer shell".*?</footer>',
                       (website / "download.html").read_text(encoding="utf-8"), re.S)[0]
    template = (website / "document.html").read_text(encoding="utf-8")
    for relative, filename in DOCUMENTS.items():
        source = Path(relative)
        text = (project / source).read_text(encoding="utf-8-sig")
        headings = []
        seen = set()
        md = MarkdownIt("commonmark", {"html": False}).enable("table")
        tokens = md.parse(text)
        if source.suffix == ".md":
            for index, token in enumerate(tokens):
                if token.type == "heading_open":
                    label = tokens[index + 1].content
                    identifier = re.sub(r"[^\w\- ]", "", label.lower()).replace(" ", "-") or "section"
                    base = identifier
                    suffix = 1
                    while identifier in seen:
                        identifier = f"{base}-{suffix}"
                        suffix += 1
                    seen.add(identifier)
                    token.attrSet("id", identifier)
                    headings.append((int(token.tag[1:]), label, identifier))
                if token.type == "inline":
                    for child in token.children or []:
                        if child.type == "link_open":
                            href = project_link(child.attrGet("href") or "", source, repository)
                            child.attrSet("href", href)
                            if urlparse(href).scheme == "https":
                                child.attrSet("target", "_blank")
                                child.attrSet("rel", "noopener noreferrer")
                        elif child.type == "image":
                            image = child.attrGet("src") or ""
                            if not urlparse(image).scheme:
                                image_source = (project / source.parent / image).resolve()
                                if not image_source.is_relative_to(project.resolve()):
                                    raise ValueError("Document image escapes the project")
                                destination = Path("assets/documents") / image_source.relative_to(project)
                                (output / destination).parent.mkdir(parents=True, exist_ok=True)
                                shutil.copy2(image_source, output / destination)
                                child.attrSet("src", destination.as_posix())
            title = next((label for level, label, _ in headings if level == 1), source.stem)
            content = md.renderer.render(tokens, md.options, {})
            content = content.replace("<table>", '<div class="document-table"><table>').replace("</table>", "</table></div>")
        else:
            title = "GNU GPL v3" if relative == "LICENSE" else "第三方来源与许可"
            content = f'<h1>{escape(title)}</h1><pre class="document-plain">{escape(text)}</pre>'
        contents = ""
        if any(level in (2, 3) for level, _, _ in headings):
            links = "".join(f'<a href="#{escape(identifier)}" data-level="{level}">{escape(label)}</a>'
                            for level, label, identifier in headings if level in (2, 3))
            contents = f'<aside class="document-toc"><p>本页内容</p><nav aria-label="本页内容">{links}</nav></aside>'
        replacements = {"__TITLE__": escape(title), "__DESCRIPTION__": escape(f"财小通{title}。"),
                        "__NAVIGATION__": navigation, "__CONTENT__": content,
                        "__CONTENTS__": contents, "__FOOTER__": footer}
        page = template
        for marker, value in replacements.items():
            page = page.replace(marker, value)
        (output / filename).write_text(page, encoding="utf-8")
    print(f"Rendered {len(DOCUMENTS)} project documents, including analytics, life services and licenses")


class References(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.ids = set()

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            self.ids.add(attrs["id"])
        for name in ("href", "src", "srcset"):
            if name in attrs:
                self.links.append(attrs[name])


def validate_site(output):
    documents = {}
    for path in output.glob("*.html"):
        parser = References()
        parser.feed(path.read_text(encoding="utf-8"))
        documents[path.resolve()] = parser
    for path, parser in documents.items():
        for link in parser.links:
            parsed = urlparse(link)
            if parsed.scheme or parsed.netloc:
                continue
            target = (path.parent / unquote(parsed.path)).resolve() if parsed.path else path
            if not target.is_relative_to(output.resolve()) or not target.is_file():
                raise ValueError(f"Broken local link in {path.name}: {link}")
            if parsed.fragment and target in documents and unquote(parsed.fragment) not in documents[target].ids:
                raise ValueError(f"Missing local heading in {path.name}: {link}")
    print("All generated pages and local links verified")
