#!/usr/bin/env python3
"""评测批次 CLI：启动、等待、导出、对比、门禁。

只依赖标准库。默认服务 http://localhost:8080，可用 EVAL_API 覆盖。

用法：
  python3 backend/scripts/eval.py agent --label baseline-42
  python3 backend/scripts/eval.py retrieval --top-k 5
  python3 backend/scripts/eval.py list --kind AGENT
  python3 backend/scripts/eval.py show <batch-id> --save
  python3 backend/scripts/eval.py compare <baseline-id> <candidate-id>

退出码：batch 失败或门禁未通过返回 1，可直接用于 CI/脚本。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_API = os.environ.get("EVAL_API", "http://localhost:8080")
POLL_SECONDS = 5


def request(method: str, url: str, body: dict | None = None) -> dict | list:
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={"Content-Type": "application/json"} if data else {},
    )
    try:
        with urllib.request.urlopen(req) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        detail = error.read().decode()
        raise SystemExit(f"HTTP {error.code}: {detail}") from error
    except urllib.error.URLError as error:
        raise SystemExit(f"无法连接 {url}: {error.reason}") from error


def now_label() -> str:
    return datetime.now().strftime("%m%d-%H%M")


def slug(text: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", text).strip("-")
    return cleaned or "batch"


def default_save(kind: str, label: str) -> Path:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return ROOT / "docs" / "eval" / f"{kind.lower()}-{slug(label)}-{stamp}.json"


def save_json(path: Path, payload: dict | list) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n")
    try:
        shown = path.resolve().relative_to(ROOT)
    except ValueError:
        shown = path.resolve()
    print(f"saved: {shown}")


def git_sha(explicit: str | None) -> str:
    if explicit:
        return explicit
    try:
        import subprocess
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, text=True).strip()
    except Exception:
        return "unknown"


def wait_for(api: str, batch_id: str) -> dict:
    last = ""
    while True:
        detail = request("GET", f"{api}/api/eval/batches/{batch_id}")
        batch = detail["batch"]
        progress = f"{batch['progressDone']}/{batch['progressTotal']}"
        status = batch["status"]
        if progress != last:
            print(f"  {status} {progress}")
            last = progress
        if status != "RUNNING":
            return detail
        time.sleep(POLL_SECONDS)


def print_summary(detail: dict) -> None:
    batch = detail["batch"]
    print(f"batch {batch['id']} {batch['status']} · {batch['label']}")
    print(f"dataset {batch['datasetVersion'][:12]} ({batch['datasetSize']}) · git {batch['gitSha']}")
    if batch.get("error"):
        print(f"error: {batch['error']}")
    if batch.get("summary"):
        print(json.dumps(batch["summary"], ensure_ascii=False, indent=2))
    failed = [case for case in detail.get("cases", []) if not case["passed"]]
    if failed:
        print(f"failed cases: {len(failed)}")
        for case in failed[:20]:
            print(f"  [{case['kind']}] {case['question']} · {case['failureKind']} · "
                  f"expected={case['expected']} actual={case['actual']}")


def start_batch(args: argparse.Namespace) -> None:
    body: dict = {"gitSha": git_sha(args.git_sha)}
    if args.label:
        body["label"] = args.label
    if args.note:
        body["note"] = args.note
    if args.baseline:
        body["baselineId"] = args.baseline

    if args.command == "agent":
        if args.threshold is not None:
            body["threshold"] = args.threshold
        if args.max_depth is not None:
            body["maxDepth"] = args.max_depth
        if args.max_probes is not None:
            body["maxProbes"] = args.max_probes
        if args.query_budget is not None:
            body["queryBudget"] = args.query_budget
        if args.token_budget is not None:
            body["tokenBudget"] = args.token_budget
        if args.max_steps is not None:
            body["maxSteps"] = args.max_steps
        if args.lookup_token_budget is not None:
            body["lookupTokenBudget"] = args.lookup_token_budget
        if args.model_proposer != "inherit":
            body["modelProposerEnabled"] = args.model_proposer == "on"
        if args.proposer_variant:
            body["proposerVariant"] = args.proposer_variant
        if args.reporter_variant:
            body["reporterVariant"] = args.reporter_variant
        endpoint = "/api/eval/agent/runs"
    else:
        if args.top_k is not None:
            body["topK"] = args.top_k
        endpoint = "/api/eval/retrieval/runs"

    batch = request("POST", f"{args.api}{endpoint}", body)
    print(f"started {batch['kind']} batch {batch['id']} ({batch['label']})")
    if args.no_wait:
        return

    detail = wait_for(args.api, batch["id"])
    print_summary(detail)
    path = Path(args.save) if isinstance(args.save, str) else default_save(
        batch["kind"], batch["label"] or now_label())
    save_json(path, detail)
    if detail["batch"]["status"] != "SUCCEEDED":
        raise SystemExit(1)


def list_batches(args: argparse.Namespace) -> None:
    query = f"?limit={args.limit}"
    if args.kind:
        query = f"?kind={args.kind}&limit={args.limit}"
    batches = request("GET", f"{args.api}/api/eval/batches{query}")
    for batch in batches:
        summary = batch.get("summary") or {}
        print(f"{batch['id']}  {batch['kind']:9} {batch['status']:9} "
              f"{batch['progressDone']:>3}/{batch['progressTotal']:<3} "
              f"{(batch['label'] or ''):32} {batch['startedAt'][:19]}")
        if summary and batch["kind"] == "AGENT":
            overall = summary.get("overall", {})
            print(f"    pass {overall.get('passed')}/{overall.get('cases')} "
                  f"rate={overall.get('passRate')}")
        elif summary and batch["kind"] == "RETRIEVAL":
            modes = ", ".join(f"{m['mode']}={m['recallAtK']}" for m in summary.get("modes", []))
            print(f"    recall {modes}")


def show_batch(args: argparse.Namespace) -> None:
    detail = request("GET", f"{args.api}/api/eval/batches/{args.batch_id}")
    print_summary(detail)
    if args.save:
        path = Path(args.save) if isinstance(args.save, str) else default_save(
            detail["batch"]["kind"], detail["batch"]["label"] or args.batch_id[:8])
        save_json(path, detail)


def compare(args: argparse.Namespace) -> None:
    comparison = request(
        "POST",
        f"{args.api}/api/eval/compare?baseline={args.baseline}&candidate={args.candidate}",
        {},
    )
    gate = comparison["gate"]
    print(f"gate: {'PASS' if gate['passed'] else 'FAIL'}")
    for violation in gate["violations"]:
        print(f"  VIOLATION {violation}")
    print(f"\n{'metric':32} {'baseline':>10} {'candidate':>10} {'delta':>10}")
    for metric in comparison["metrics"]:
        print(f"{metric['name']:32} {metric['baseline']:>10} {metric['candidate']:>10} "
              f"{metric['delta']:>+10}")
    counts: dict[str, int] = {}
    for case in comparison.get("cases", []):
        counts[case["status"]] = counts.get(case["status"], 0) + 1
    if counts:
        print(f"\ncase diffs: {counts}")
    for case in comparison.get("cases", []):
        if case["status"] in ("REGRESSED", "IMPROVED"):
            print(f"  {case['status']:9} [{case['kind']}] {case['question']} "
                  f"{case['baselineActual']} -> {case['candidateActual']}")
    if args.save:
        path = Path(args.save) if isinstance(args.save, str) else default_save(
            "comparison", f"{args.baseline[:8]}-{args.candidate[:8]}")
        save_json(path, comparison)
    if not gate["passed"]:
        raise SystemExit(1)


def parser() -> argparse.ArgumentParser:
    top = argparse.ArgumentParser(description="Evaluation batch CLI")
    top.add_argument("--api", default=DEFAULT_API)
    sub = top.add_subparsers(dest="command", required=True)

    agent = sub.add_parser("agent", help="run an agent batch")
    agent.add_argument("--label")
    agent.add_argument("--note")
    agent.add_argument("--baseline")
    agent.add_argument("--threshold", type=float)
    agent.add_argument("--max-depth", type=int)
    agent.add_argument("--max-probes", type=int)
    agent.add_argument("--query-budget", type=int)
    agent.add_argument("--token-budget", type=int)
    agent.add_argument("--max-steps", type=int)
    agent.add_argument("--lookup-token-budget", type=int)
    agent.add_argument("--model-proposer", choices=["inherit", "on", "off"], default="inherit")
    agent.add_argument("--proposer-variant")
    agent.add_argument("--reporter-variant")
    agent.add_argument("--git-sha")
    agent.add_argument("--no-wait", action="store_true")
    agent.add_argument("--save", nargs="?", const=True)

    retrieval = sub.add_parser("retrieval", help="run a retrieval batch")
    retrieval.add_argument("--label")
    retrieval.add_argument("--note")
    retrieval.add_argument("--baseline")
    retrieval.add_argument("--top-k", type=int)
    retrieval.add_argument("--git-sha")
    retrieval.add_argument("--no-wait", action="store_true")
    retrieval.add_argument("--save", nargs="?", const=True)

    listing = sub.add_parser("list", help="list batches")
    listing.add_argument("--kind", choices=["AGENT", "RETRIEVAL"])
    listing.add_argument("--limit", type=int, default=20)

    show = sub.add_parser("show", help="show/export one batch")
    show.add_argument("batch_id")
    show.add_argument("--save", nargs="?", const=True)

    comp = sub.add_parser("compare", help="compare two batches and enforce the gate")
    comp.add_argument("baseline")
    comp.add_argument("candidate")
    comp.add_argument("--save", nargs="?", const=True)

    agent.set_defaults(func=start_batch)
    retrieval.set_defaults(func=start_batch)
    listing.set_defaults(func=list_batches)
    show.set_defaults(func=show_batch)
    comp.set_defaults(func=compare)
    return top


def main() -> None:
    args = parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
