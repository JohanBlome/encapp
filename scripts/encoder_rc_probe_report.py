#!/usr/bin/env python3
"""Render an encoder RC probe Markdown report as self-contained HTML."""

from __future__ import annotations

import argparse
import base64
import html
import mimetypes
from pathlib import Path
import re
from typing import List, Sequence


_IMAGE_RE = re.compile(r"^!\[([^]]*)\]\(([^)]+)\)$")
_HEADING_RE = re.compile(r"^(#{1,6})\s+(.*)$")
_TABLE_SEPARATOR_RE = re.compile(r"^:?-{3,}:?$")
_INLINE_CODE_RE = re.compile(r"`([^`]+)`")


def _inline(value: str) -> str:
    escaped = html.escape(value, quote=False)
    return _INLINE_CODE_RE.sub(
        lambda match: f"<code>{match.group(1)}</code>", escaped)


def _table_cells(line: str) -> List[str]:
    return [cell.strip() for cell in line.strip().strip("|").split("|")]


def _is_separator_row(cells: Sequence[str]) -> bool:
    return bool(cells) and all(_TABLE_SEPARATOR_RE.fullmatch(cell) for cell in cells)


def _embedded_image(markdown_path: Path, alt: str, target: str) -> str:
    image_path = (markdown_path.parent / target).resolve()
    if not image_path.is_file():
        return f'<p class="missing">Missing image: <code>{html.escape(target)}</code></p>'
    mime = mimetypes.guess_type(image_path.name)[0] or "application/octet-stream"
    encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
    return (
        "<figure>"
        f'<img src="data:{mime};base64,{encoded}" alt="{html.escape(alt, quote=True)}">'
        f"<figcaption>{_inline(alt)}</figcaption>"
        "</figure>")


def markdown_to_html(markdown_path: Path) -> str:
    lines = markdown_path.read_text(encoding="utf-8").splitlines()
    output: List[str] = []
    index = 0
    while index < len(lines):
        line = lines[index].rstrip()
        stripped = line.strip()
        if not stripped or stripped.startswith("<!--"):
            index += 1
            continue
        heading = _HEADING_RE.match(stripped)
        if heading:
            level = len(heading.group(1))
            output.append(f"<h{level}>{_inline(heading.group(2))}</h{level}>")
            index += 1
            continue
        image = _IMAGE_RE.match(stripped)
        if image:
            output.append(_embedded_image(markdown_path, image.group(1), image.group(2)))
            index += 1
            continue
        if stripped.startswith("|"):
            table_lines: List[str] = []
            while index < len(lines) and lines[index].strip().startswith("|"):
                table_lines.append(lines[index].strip())
                index += 1
            rows = [_table_cells(value) for value in table_lines]
            if len(rows) >= 2 and _is_separator_row(rows[1]):
                output.append('<div class="table-wrap"><table><thead><tr>')
                output.extend(f"<th>{_inline(cell)}</th>" for cell in rows[0])
                output.append("</tr></thead><tbody>")
                for row in rows[2:]:
                    output.append("<tr>")
                    output.extend(f"<td>{_inline(cell)}</td>" for cell in row)
                    output.append("</tr>")
                output.append("</tbody></table></div>")
            else:
                output.extend(f"<p>{_inline(value)}</p>" for value in table_lines)
            continue
        if stripped.startswith("- "):
            output.append("<ul>")
            while index < len(lines) and lines[index].strip().startswith("- "):
                output.append(f"<li>{_inline(lines[index].strip()[2:])}</li>")
                index += 1
            output.append("</ul>")
            continue
        paragraph = [stripped]
        index += 1
        while index < len(lines):
            candidate = lines[index].strip()
            if (not candidate or candidate.startswith("#") or candidate.startswith("|")
                    or candidate.startswith("- ") or _IMAGE_RE.match(candidate)
                    or candidate.startswith("<!--")):
                break
            paragraph.append(candidate)
            index += 1
        output.append(f"<p>{_inline(' '.join(paragraph))}</p>")

    css = """
:root { color-scheme: light dark; }
body { font: 16px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
       max-width: 1500px; margin: 0 auto; padding: 24px 32px 80px; }
h1, h2, h3 { line-height: 1.2; margin-top: 1.6em; }
code { background: color-mix(in srgb, CanvasText 8%, Canvas); padding: .12em .3em;
       border-radius: 4px; }
.table-wrap { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: 13px; }
th, td { border: 1px solid color-mix(in srgb, CanvasText 20%, Canvas);
         padding: 5px 8px; text-align: left; white-space: nowrap; }
th { position: sticky; top: 0; background: Canvas; }
figure { margin: 20px 0 52px; break-inside: avoid; }
img { display: block; width: 100%; height: auto; border-radius: 6px;
      box-shadow: 0 1px 8px color-mix(in srgb, CanvasText 18%, transparent); }
figcaption { margin-top: 8px; color: color-mix(in srgb, CanvasText 65%, Canvas); }
.missing { color: #b42318; }
"""
    title = markdown_path.stem
    return (
        "<!doctype html><html><head><meta charset=\"utf-8\">"
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        f"<title>{html.escape(title)}</title><style>{css}</style></head><body>"
        + "\n".join(output) + "</body></html>\n")


def write_html_report(markdown_path: Path) -> Path:
    output = markdown_path.with_suffix(".html")
    output.write_text(markdown_to_html(markdown_path), encoding="utf-8")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="REPORT.md to render")
    args = parser.parse_args()
    report = args.report.resolve()
    if not report.is_file():
        parser.error(f"report not found: {report}")
    print(write_html_report(report))


if __name__ == "__main__":
    main()
