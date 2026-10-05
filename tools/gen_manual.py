#!/usr/bin/env python3
"""Turns manual/*.md into the manual the app carries.

The manual is Markdown in manual/, so it reads on the git host, and the app's
Help screen shows the same words from shared/.../ui/Manual.kt, which this
writes. It also writes the contents list in manual/README.md.

The manual uses this much Markdown:

    # Title              the section, one per file
    > summary            the line under it in the contents
    ## Heading           a heading inside the section
    paragraph            run of lines, joined
    - bullet             a list item
    1. step              a numbered item
    `code` and **bold**  left in the text, drawn by the Help screen

Run it after editing the manual:  python3 tools/gen_manual.py
Check both are up to date:        python3 tools/gen_manual.py --check
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "manual"
OUT = ROOT / "shared/src/commonMain/kotlin/com/rm/parrotmetric/ui/Manual.kt"
INDEX = SRC / "README.md"
OPEN, CLOSE = "<!-- contents -->", "<!-- /contents -->"
LINK = re.compile(r"\[([^\]]+)\]\([^)]+\)")


def parse(path):
    """One file as (title, summary, [(kind, text)])."""
    title, summary, blocks, para = None, "", [], []

    def flush():
        if para:
            blocks.append(("Para", " ".join(para)))
            para.clear()

    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.rstrip()
        if not line.strip():
            flush()
        elif line.startswith("# "):
            flush(); title = line[2:].strip()
        elif line.startswith("> "):
            flush(); summary = line[2:].strip()
        elif line.startswith("## "):
            flush(); blocks.append(("Heading", line[3:].strip()))
        elif line.startswith("- "):
            flush(); blocks.append(("Bullet", line[2:].strip()))
        elif re.match(r"^\d+\. ", line):
            flush(); blocks.append(("Step", line.split(". ", 1)[1].strip()))
        elif line.startswith("  ") and blocks and blocks[-1][0] in ("Bullet", "Step") and not para:
            kind, text = blocks[-1]
            blocks[-1] = (kind, text + " " + line.strip())
        else:
            para.append(line.strip())
    flush()
    if title is None:
        sys.exit(f"gen_manual: {path} has no '# Title' line")
    return title, summary, blocks


def q(s):
    s = LINK.sub(r"\1", s)
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def kotlin(sections):
    out = [
        "package com.rm.parrotmetric.ui",
        "",
        "// Written by tools/gen_manual.py from manual/. Edit the Markdown there and",
        "// run the script again, not this.",
        "",
        "/** What a line of the manual is. `code` and **bold** stay in the text. */",
        "enum class ManualKind { Heading, Para, Bullet, Step }",
        "",
        "class ManualBlock(val kind: ManualKind, val text: String)",
        "",
        "class ManualSection(val title: String, val summary: String, val blocks: List<ManualBlock>)",
        "",
        "object Manual {",
        "    val sections: List<ManualSection> = listOf(",
    ]
    for title, summary, blocks in sections:
        out.append(f"        ManualSection({q(title)}, {q(summary)}, listOf(")
        for kind, text in blocks:
            out.append(f"            ManualBlock(ManualKind.{kind}, {q(text)}),")
        out.append("        )),")
    out += ["    )", "}", ""]
    return "\n".join(out)


def indexed(files, sections):
    text = INDEX.read_text(encoding="utf-8")
    a, b = text.find(OPEN), text.find(CLOSE)
    if a < 0 or b < 0:
        sys.exit(f"gen_manual: {INDEX} has no {OPEN} ... {CLOSE}")
    rows = []
    for i, (path, (title, summary, _)) in enumerate(zip(files, sections), 1):
        # "Extrude, ..." reads on as "- extrude, ...", but "STL" stays as it is.
        start = summary[:1] if summary[1:2].isupper() else summary[:1].lower()
        tail = f" - {start + summary[1:]}" if summary else ""
        rows.append(f"{i}. [{title}]({path.name}){tail}")
    return text[:a] + "\n".join([OPEN, ""] + rows + ["", CLOSE]) + text[b + len(CLOSE):]


def main():
    files = sorted(p for p in SRC.glob("*.md") if p.name != "README.md")
    sections = [parse(p) for p in files]
    text, index = kotlin(sections), indexed(files, sections)
    words = sum(len(t.split()) for _, _, bs in sections for _, t in bs)
    if "--check" in sys.argv:
        for path, want in ((OUT, text), (INDEX, index)):
            if not path.exists() or path.read_text(encoding="utf-8") != want:
                sys.exit(f"gen_manual: {path} is out of date; run python3 tools/gen_manual.py")
        print(f"gen_manual: {len(sections)} sections, {words} words, up to date")
        return
    OUT.write_text(text, encoding="utf-8")
    INDEX.write_text(index, encoding="utf-8")
    print(f"gen_manual: {len(sections)} sections, {words} words")


main()
