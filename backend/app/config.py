import os
from dataclasses import dataclass, field
from decimal import Decimal, ROUND_CEILING
from urllib.parse import urlparse


MICROS = Decimal(1_000_000)


def money(value: Decimal) -> int:
    return int((value * MICROS).to_integral_value(rounding=ROUND_CEILING))


@dataclass(frozen=True)
class Settings:
    db_path: str = "data/companion.sqlite3"
    api_enabled: bool = False
    mock_mode: bool = False
    public_enabled: bool = True
    daily_limit: int = 50
    user_daily_limit: int = 5
    admin_token: str = field(default="", repr=False)
    admin_password_hash: str = field(default="", repr=False)
    feedback_worker_token: str = field(default="", repr=False)
    api_key: str = field(default="", repr=False)
    base_url: str = "https://api.deepseek.com"
    model: str = "deepseek-flash"
    global_budget_cny: Decimal = Decimal(0)
    monthly_budget_cny: Decimal = Decimal(0)
    user_budget_cny: Decimal = Decimal(0)
    per_job_limit_cny: Decimal = Decimal(0)
    input_price_per_million: Decimal = Decimal(0)
    output_price_per_million: Decimal = Decimal(0)
    context_tokens: int = 32768
    max_output_tokens: int = 2048
    provider_timeout_seconds: float = 90
    cleanup_interval_seconds: float = 30
    worker_enabled: bool = True
    max_body_bytes: int = 65536
    max_response_bytes: int = 65536
    failure_artifacts_enabled: bool = True
    failure_artifacts_ttl_hours: int = 24

    @classmethod
    def from_env(cls):
        defaults = cls()
        values = {}
        for name in cls.__dataclass_fields__:
            raw = os.environ.get(name.upper())
            if raw is None:
                continue
            default = getattr(defaults, name)
            if isinstance(default, bool):
                if raw.lower() not in {"true", "false", "1", "0"}:
                    raise ValueError(f"{name.upper()} 必须为 true 或 false")
                values[name] = raw.lower() in {"true", "1"}
            elif isinstance(default, Decimal):
                values[name] = Decimal(raw)
            elif isinstance(default, int):
                values[name] = int(raw)
            elif isinstance(default, float):
                values[name] = float(raw)
            else:
                values[name] = raw
        result = cls(**values)
        result.validate()
        return result

    def validate(self):
        if type(self.failure_artifacts_enabled) is not bool:
            raise ValueError("FAILURE_ARTIFACTS_ENABLED 必须为 true 或 false")
        if type(self.failure_artifacts_ttl_hours) is not int or not 1 <= self.failure_artifacts_ttl_hours <= 168:
            raise ValueError("FAILURE_ARTIFACTS_TTL_HOURS 须为 1 到 168 的整数")
        if not 0 <= self.daily_limit <= 1_000_000 or not 0 <= self.user_daily_limit <= 1_000_000:
            raise ValueError("每日次数上限须在 0 到 1000000 之间")
        if not self.model.strip() or len(self.model) > 128 or any(character.isspace() for character in self.model):
            raise ValueError("模型名称须为 1 到 128 个非空白字符")
        if len(self.api_key) > 4096 or "\n" in self.api_key or "\r" in self.api_key:
            raise ValueError("服务密钥长度或格式无效")
        if self.feedback_worker_token:
            if (not 32 <= len(self.feedback_worker_token) <= 512
                    or any(character.isspace() for character in self.feedback_worker_token)
                    or self.feedback_worker_token in {self.admin_token, self.api_key}):
                raise ValueError("FEEDBACK_WORKER_TOKEN 须为独立的 32 到 512 字符密钥")
            try:
                self.feedback_worker_token.encode("utf-8")
            except UnicodeError:
                raise ValueError("FEEDBACK_WORKER_TOKEN 格式无效") from None
        for name in ("global_budget_cny", "monthly_budget_cny", "user_budget_cny",
                     "per_job_limit_cny", "input_price_per_million", "output_price_per_million"):
            value = getattr(self, name)
            if not value.is_finite() or value < 0 or value > Decimal("100000000"):
                raise ValueError(f"{name} 必须是有限的非负金额")
        if not 256 <= self.max_output_tokens <= 8192:
            raise ValueError("MAX_OUTPUT_TOKENS 应在 256 到 8192 之间")
        if not 512 <= self.context_tokens <= 262144 or self.context_tokens <= self.max_output_tokens:
            raise ValueError("CONTEXT_TOKENS 必须大于输出上限，且不超过 262144")
        if not 1 <= self.cleanup_interval_seconds <= 60:
            raise ValueError("清理间隔须在 1 到 60 秒之间")
        if not 1 <= self.provider_timeout_seconds <= 300:
            raise ValueError("请求超时须在 1 到 300 秒之间")
        if not 4096 <= self.max_body_bytes <= 262144 or not 4096 <= self.max_response_bytes <= 262144:
            raise ValueError("请求和响应上限应在 4096 到 262144 字节之间")
        parsed = urlparse(self.base_url)
        if (len(self.base_url) > 2048 or parsed.scheme != "https" or not parsed.hostname
                or parsed.username or parsed.password or parsed.query or parsed.fragment):
            raise ValueError("模型服务必须使用不含凭据和查询参数的 HTTPS 地址")

    @property
    def upstream_ready(self):
        return self.mock_mode or (self.api_enabled and bool(self.api_key))

    @property
    def cost_known(self):
        return self.input_price_per_million > 0 and self.output_price_per_million > 0

    @property
    def enabled(self):
        caps = (self.global_budget_cny, self.monthly_budget_cny,
                self.user_budget_cny, self.per_job_limit_cny,
                self.input_price_per_million, self.output_price_per_million)
        configured = all(value > 0 for value in caps)
        return configured and self.upstream_ready

    @property
    def display_model(self):
        return f"MOCK/{self.model}" if self.mock_mode else self.model
