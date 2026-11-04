import argparse
import json
from decimal import Decimal

from .config import Settings, money
from .store import Store


def amount(value):
    try:
        parsed = Decimal(value)
    except ArithmeticError:
        raise argparse.ArgumentTypeError("请输入有限的非负人民币金额") from None
    if not parsed.is_finite() or not 0 <= parsed <= Decimal("100000000"):
        raise argparse.ArgumentTypeError("请输入有限的非负人民币金额")
    return money(parsed)


def main():
    parser = argparse.ArgumentParser(description="续聊助手本机邀请码和历史金额账务管理工具")
    commands = parser.add_subparsers(dest="command", required=True)
    invite = commands.add_parser("invite", help="生成随机邀请码或幂等配置指定邀请码")
    invite.add_argument("--code", help="指定 4–128 位字母、数字、下划线或连字符；已有邀请码保持原配置")
    invite.add_argument("--uses", type=int, default=1)
    invite.add_argument("--days", type=int, default=7)
    invite.add_argument("--user-budget", type=amount)
    cap = commands.add_parser("cap", help="更新数据库预算上限")
    cap.add_argument("name", choices=["global", "monthly", "user", "per_job"])
    cap.add_argument("cny", type=amount)
    reconcile = commands.add_parser("reconcile", help="依据服务方账单结算不确定任务")
    reconcile.add_argument("job_id")
    reconcile.add_argument("--actual-cny", type=amount, required=True)
    commands.add_parser("uncertain", help="仅列出任务编号和账务信息")
    commands.add_parser("purge", help="立即删除到期原文和结果")
    args = parser.parse_args()
    settings = Settings.from_env()
    store = Store(settings)
    store.initialize()
    try:
        if args.command == "invite":
            print(store.create_invitation(args.uses, args.user_budget, args.days, args.code))
        elif args.command == "cap":
            store.set_cap(args.name, args.cny)
            print("历史邀请码金额上限已更新。公开免费模式按每日次数管理。")
        elif args.command == "reconcile":
            store.reconcile(args.job_id, args.actual_cny)
            print("已结算，任务不会自动重试。")
        elif args.command == "purge":
            print(f"已清理 {store.purge_expired()} 个到期任务的内容。")
        else:
            with store.connection() as db:
                rows = db.execute("SELECT id, owner_id, reserved, cost, created_at, cancel_requested FROM jobs WHERE status='uncertain'")
                print(json.dumps([dict(row) for row in rows], ensure_ascii=False, indent=2))
    except (ValueError, ArithmeticError) as error:
        parser.exit(2, f"操作失败：{error}\n")


if __name__ == "__main__":
    main()
