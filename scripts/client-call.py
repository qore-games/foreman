#!/usr/bin/env python3
"""Calls a tool of a running client's MCP server (outside access on), for agents whose session listed the client's
tools before the mod was rebuilt: new tools work at once.

    client-call.py --connection <game dir>/config/foreman/client-mcp.json <tool> ['<json args>']
    client-call.py --url <url> --token <token> --list

The connection file is the one the mod writes while outside access is on (`FOREMAN_CLIENT_MCP` can name it instead).
Prints the text blocks; images are written to temporary files whose paths are printed.
"""
import argparse
import base64
import json
import os
import sys
import tempfile
import urllib.request


def request(url, token, method, params):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    req = urllib.request.Request(url, body, {"Content-Type": "application/json", "Authorization": "Bearer " + token})
    with urllib.request.urlopen(req, timeout=900) as response:
        answer = json.load(response)
    if "error" in answer:
        sys.exit(f"{method}: {answer['error'].get('message')}")
    return answer["result"]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--connection", default=os.environ.get("FOREMAN_CLIENT_MCP"), help="the client's client-mcp.json")
    parser.add_argument("--url", help="the client MCP's address (instead of --connection)")
    parser.add_argument("--token", help="its token (instead of --connection)")
    parser.add_argument("--list", action="store_true", help="list the tools")
    parser.add_argument("tool", nargs="?")
    parser.add_argument("arguments", nargs="?", default="{}", help="the tool's arguments, as JSON")
    args = parser.parse_args()

    url, token = args.url, args.token
    if not (url and token):
        if not args.connection:
            parser.error("give --connection (or FOREMAN_CLIENT_MCP), or --url and --token")
        with open(args.connection) as f:
            connection = json.load(f)
        url, token = url or connection["url"], token or connection["token"]

    if args.list:
        for tool in request(url, token, "tools/list", {})["tools"]:
            print(f"{tool['name']}: {tool['description'][:120]}")
        return
    if not args.tool:
        parser.error("give a tool, or --list")
    result = request(url, token, "tools/call", {"name": args.tool, "arguments": json.loads(args.arguments)})
    for i, block in enumerate(result.get("content", [])):
        if block.get("type") == "text":
            print(block["text"])
        elif block.get("type") == "image":
            fd, path = tempfile.mkstemp(prefix=f"foreman-{args.tool}-{i}-", suffix=".png")
            with os.fdopen(fd, "wb") as out:
                out.write(base64.b64decode(block["data"]))
            print(f"[image: {path}]")
    if result.get("isError"):
        sys.exit(1)


if __name__ == "__main__":
    main()
