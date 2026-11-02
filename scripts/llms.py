"""Writes llms.txt (an index of the documentation, https://llmstxt.org) and llms-full.txt (every page, in the order of the
navigation) into the built site, for AIs that read the docs.

Run after `zensical build`: python3 -I scripts/llms.py [site dir, default site]
"""
import re
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCS = ROOT / 'docs'
RAW = 'https://raw.githubusercontent.com/qore-games/foreman/main/docs/'


def pages(nav, section=''):
    """(section, title, path) of each page, in the navigation's order."""
    for item in nav:
        if isinstance(item, str):
            yield section, None, item
            continue
        for title, value in item.items():
            if isinstance(value, str):
                yield section, title, value
            else:
                yield from pages(value, title if not section else f'{section} / {title}')


def summary(text):
    """The page's first paragraph, as one line."""
    for block in re.split(r'\n\s*\n', re.sub(r'^---.*?---\n', '', text, flags=re.S)):
        block = block.strip()
        if block and not block.startswith(('#', '<', '!', '|', '```', '===', '>')):
            return re.sub(r'\s+', ' ', re.sub(r'\[([^\]]+)\]\([^)]*\)', r'\1', block))
    return ''


def main():
    site = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / 'site'
    project = tomllib.loads((ROOT / 'zensical.toml').read_text())['project']
    entries = []
    for section, title, path in pages(project['nav']):
        text = (DOCS / path).read_text()
        heading = re.search(r'^# (.+)$', text, re.M)
        entries.append((section, title or (heading.group(1) if heading else path), path, text))

    index = [f"# {project['site_name']}", '', f"> {project['site_description']}. A Fabric client mod (any server) and an "
             "optional Paper plugin for Minecraft: a packet viewer, a HUD inspector, plugin locations in the world, "
             "dev-server tools, AI chats in game, MCP servers for outside agents, world tools, skills and integrations "
             "for other plugins, and APIs for plugin developers.", '',
             f"Every page in one file: {project['site_url']}llms-full.txt", '']
    current = None
    for section, title, path, text in entries:
        name = section or 'Overview'
        if name != current:
            index += ['', f'## {name}', '']
            current = name
        index.append(f'- [{title}]({RAW}{path}): {summary(text)}')
    (site / 'llms.txt').write_text('\n'.join(index).strip() + '\n')

    full = [f"# {project['site_name']}: the documentation", '', f"> {project['site_description']}.", '']
    for section, title, path, text in entries:
        full += ['', '---', '', f'<!-- {path} ({section or "Overview"} / {title}) -->', '', text.strip()]
    (site / 'llms-full.txt').write_text('\n'.join(full).strip() + '\n')
    print(f"{site / 'llms.txt'}, {site / 'llms-full.txt'}: {len(entries)} pages")


if __name__ == '__main__':
    main()
