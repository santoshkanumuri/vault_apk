"""Rebuilds the search-engine files of the Nuvori website (card_app_windows/landing) from its HTML.

    python scripts/landing/seo.py [path/to/card_app_windows]          # write
    python scripts/landing/seo.py [path/to/card_app_windows] --check  # change nothing; exit 1 if out of date

What it does:
1. FAQPage structured data. Reads every <details class="qa" id="..."> in landing/faq/index.html and writes the
   questions and answers as JSON-LD between the "seo:faq-jsonld" markers in the same file's <head>.
   Answers keep only the HTML that Google accepts in FAQ answers (p, ul, ol, li, a, strong, b, em, br).
   Screenshots, captions and comments are left out. Links become absolute.
2. sitemap.xml. One <url> for every landing/**/index.html page without <meta name="robots" content="noindex">.
   lastmod is the date of the page's last commit, or today when the page has uncommitted changes.
3. Checks: each page has one absolute canonical URL that matches its path, and every JSON-LD block parses.

Python 3.9+, standard library only. Git is used read-only (log and status).
"""
import datetime
import html
import json
import pathlib
import re
import subprocess
import sys
from html.parser import HTMLParser
from urllib.parse import urljoin

SITE = "https://nuvori.santoshkanumuri.com/"
HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_WINDOWS = HERE.parent.parent.parent / "card_app_windows"
START, END = "<!-- seo:faq-jsonld:start -->", "<!-- seo:faq-jsonld:end -->"
ALLOWED = {"p", "ul", "ol", "li", "a", "strong", "b", "em", "i", "br"}
SKIP = {"figure", "img", "figcaption", "script", "style", "svg", "button"}
VOID = {"br", "img", "hr", "input", "meta", "link", "source", "wbr"}


class Node:
    def __init__(self, tag, attrs, parent=None):
        self.tag, self.attrs, self.parent, self.children = tag, dict(attrs), parent, []

    def classes(self):
        return (self.attrs.get("class") or "").split()

    def find_all(self, test):
        for child in self.children:
            if isinstance(child, Node):
                if test(child):
                    yield child
                yield from child.find_all(test)

    def text(self):
        return "".join(c if isinstance(c, str) else c.text() for c in self.children)


class TreeBuilder(HTMLParser):
    """A forgiving DOM: enough for the site's own, well-formed HTML. Comments are dropped."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = self.cur = Node("#root", {})

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs, self.cur)
        self.cur.children.append(node)
        if tag not in VOID:
            self.cur = node

    def handle_startendtag(self, tag, attrs):
        self.cur.children.append(Node(tag, attrs, self.cur))

    def handle_endtag(self, tag):
        node = self.cur
        while node is not self.root and node.tag != tag:
            node = node.parent
        if node is not self.root:
            self.cur = node.parent

    def handle_data(self, data):
        self.cur.children.append(data)


def parse(text):
    builder = TreeBuilder()
    builder.feed(text)
    return builder.root


def clean(node, base):
    """The answer as Google's allowed HTML subset, with collapsed white space."""
    out = []
    for child in node.children:
        if isinstance(child, str):
            out.append(html.escape(re.sub(r"\s+", " ", child), quote=False))
            continue
        if child.tag in SKIP:
            continue
        inner = clean(child, base)
        if child.tag == "br":
            out.append("<br>")
        elif child.tag == "a":
            href = urljoin(base, child.attrs.get("href", ""))
            out.append(f'<a href="{html.escape(href)}">{inner.strip()}</a>')
        elif child.tag in ALLOWED:
            out.append(f"<{child.tag}>{inner.strip()}</{child.tag}>")
        else:
            out.append(inner)
    return re.sub(r"\s*(</?(?:p|ul|ol|li)>)\s*", r"\1", "".join(out)).strip()


def faq_jsonld(page_html):
    root = parse(page_html)
    base = SITE + "faq/"
    items, ids = [], set()
    for qa in root.find_all(lambda n: n.tag == "details" and "qa" in n.classes()):
        qid = qa.attrs.get("id")
        if not qid or qid in ids:
            raise SystemExit(f"FAQ question without a unique id: {qid!r}")
        ids.add(qid)
        question = next(qa.find_all(lambda n: "qa__q" in n.classes()), None)
        body = next(qa.find_all(lambda n: "faq__body" in n.classes()), None)
        if question is None or body is None:
            raise SystemExit(f"FAQ question {qid}: no .qa__q or .faq__body")
        items.append({
            "@type": "Question",
            "@id": f"{base}#{qid}",
            "name": re.sub(r"\s+", " ", question.text()).strip(),
            "url": f"{base}#{qid}",
            "acceptedAnswer": {"@type": "Answer", "text": clean(body, base)},
        })
    data = {"@context": "https://schema.org", "@type": "FAQPage", "@id": base + "#faq", "url": base,
            "name": "Nuvori questions and answers", "inLanguage": "en", "mainEntity": items}
    # "</" never appears raw inside the script element.
    text = json.dumps(data, ensure_ascii=False, indent=1).replace("</", "<\\/")
    return f'{START}\n  <script type="application/ld+json">\n{text}\n  </script>\n  {END}', len(items)


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=False).stdout.strip()


def lastmod(repo, path):
    rel = path.relative_to(repo).as_posix()
    if git(repo, "status", "--porcelain", "--", rel):
        return datetime.date.today().isoformat()
    return git(repo, "log", "-1", "--format=%cs", "--", rel) or datetime.date.today().isoformat()


def pages(landing):
    # The home page first, then the others by path.
    for path in sorted(landing.rglob("index.html"), key=lambda p: (p.parent != landing, p.as_posix())):
        text = path.read_text(encoding="utf-8")
        if re.search(r'<meta name="robots" content="[^"]*noindex', text):
            continue
        rel = path.parent.relative_to(landing).as_posix()
        yield path, text, SITE if rel == "." else f"{SITE}{rel}/"


def sitemap(repo, landing):
    rows = []
    for path, _, url in pages(landing):
        rows.append(f"  <url>\n    <loc>{url}</loc>\n    <lastmod>{lastmod(repo, path)}</lastmod>\n  </url>")
    return ('<?xml version="1.0" encoding="UTF-8"?>\n'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n' + "\n".join(rows) + "\n</urlset>\n")


def check_pages(landing):
    problems = []
    for path in sorted(landing.rglob("*.html")):
        text = path.read_text(encoding="utf-8")
        for block in re.findall(r'<script type="application/ld\+json">(.*?)</script>', text, re.S):
            try:
                json.loads(block)
            except ValueError as error:
                problems.append(f"{path.relative_to(landing)}: JSON-LD does not parse: {error}")
    for path, text, url in pages(landing):
        canon = re.findall(r'<link rel="canonical" href="([^"]+)">', text)
        if canon != [url]:
            problems.append(f"{path.relative_to(landing)}: canonical {canon} should be [{url}]")
    return problems


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    check = "--check" in sys.argv
    repo = pathlib.Path(args[0]).resolve() if args else DEFAULT_WINDOWS
    landing = repo / "landing"
    faq = landing / "faq" / "index.html"
    page = faq.read_text(encoding="utf-8")
    block, count = faq_jsonld(page)
    if START not in page or END not in page:
        raise SystemExit(f"{faq}: add the {START} and {END} markers in <head> first")
    new_page = re.sub(re.escape(START) + ".*?" + re.escape(END), lambda _: block, page, flags=re.S)
    site_map = sitemap(repo, landing)
    sitemap_path = landing / "sitemap.xml"
    old_map = sitemap_path.read_text(encoding="utf-8") if sitemap_path.exists() else ""
    problems = check_pages(landing)
    if check:
        locs = lambda text: re.findall(r"<loc>(.*?)</loc>", text)
        if new_page != page:
            problems.append("faq/index.html: the FAQPage JSON-LD is out of date")
        if locs(site_map) != locs(old_map):
            problems.append("sitemap.xml: the page list is out of date")
    else:
        if new_page != page:
            faq.write_text(new_page, encoding="utf-8", newline="\n")
        if site_map != old_map:
            sitemap_path.write_text(site_map, encoding="utf-8", newline="\n")
        print(f"FAQPage: {count} questions. sitemap.xml: {site_map.count('<url>')} pages.")
    for problem in problems:
        print("ERROR", problem)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
