from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


MAX_SOURCE_CHARACTERS = 40_000
MAX_SOURCE_MESSAGES = 10_000


class Message(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: str = Field(min_length=1, max_length=512)
    role: Literal["user", "assistant", "system", "tool", "developer", "unknown"]
    content: str = Field(min_length=1, max_length=MAX_SOURCE_CHARACTERS)


class JobRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    idempotency_key: str = Field(min_length=8, max_length=200, pattern=r"^[A-Za-z0-9_.:-]+$")
    messages: list[Message] = Field(min_length=1, max_length=MAX_SOURCE_MESSAGES)

    @model_validator(mode="after")
    def bounded_chunk(self):
        if sum(len(message.content) for message in self.messages) > MAX_SOURCE_CHARACTERS:
            raise ValueError(f"每次整理的正文总计不能超过 {MAX_SOURCE_CHARACTERS} 字符，请分段提交")
        if any(not message.content.strip() or not message.id.strip() for message in self.messages):
            raise ValueError("消息编号及正文不能为空白")
        # A source may be split into multiple pieces with the same role.
        roles = {}
        for message in self.messages:
            try:
                message.id.encode("utf-8")
                message.content.encode("utf-8")
            except UnicodeError:
                raise ValueError("消息包含无效 Unicode 字符") from None
            if message.id in roles and roles[message.id] != message.role:
                raise ValueError("同一来源编号不能具有不同角色")
            roles[message.id] = message.role
        return self


class RedeemRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    code: str = Field(min_length=4, max_length=128, pattern=r"^[A-Za-z0-9_-]+$")


class AdminConfigPatch(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    public_enabled: bool | None = None
    daily_limit: int | None = Field(default=None, ge=0, le=1_000_000)
    user_daily_limit: int | None = Field(default=None, ge=0, le=1_000_000)
    model: str | None = Field(default=None, min_length=1, max_length=128)
    base_url: str | None = Field(default=None, min_length=1, max_length=2048)
    api_key: str | None = Field(default=None, max_length=4096)
    context_tokens: int | None = Field(default=None, ge=512, le=262144)
    max_output_tokens: int | None = Field(default=None, ge=256, le=8192)
    provider_timeout_seconds: float | None = Field(default=None, ge=1, le=300)

    @model_validator(mode="after")
    def reject_explicit_null(self):
        if any(getattr(self, name) is None for name in self.model_fields_set):
            raise ValueError("配置字段不能为 null，请省略不修改的字段")
        return self


class GeneratedEntry(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)
    category: Literal["BACKGROUND", "PEOPLE", "DECISIONS", "RECENT", "QUOTES"]
    text: str = Field(min_length=1, max_length=2000)
    sourceIds: list[str] = Field(min_length=1, max_length=50)
    kind: Literal["USER_FACT", "ASSISTANT_SUGGESTION", "UNCONFIRMED"]
    continuityCue: Literal["PREFERENCE", "CORRECTION", "SHARED_LANGUAGE", "OPEN_THREAD"] | None = None


class GeneratedDocument(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)
    entries: list[GeneratedEntry] = Field(max_length=40)
