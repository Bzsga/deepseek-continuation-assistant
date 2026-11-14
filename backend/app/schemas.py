from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


MAX_SOURCE_CHARACTERS = 40_000
MAX_SOURCE_MESSAGES = 10_000
MAX_CONTEXT_CHARACTERS = 800
MAX_FILTER_CHARACTERS = 6000
MAX_FILTER_MESSAGES = 12


class Message(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    id: str = Field(min_length=1, max_length=512)
    role: Literal["user", "assistant", "system", "tool", "developer", "unknown"]
    content: str = Field(min_length=1, max_length=MAX_SOURCE_CHARACTERS)


class JobRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    idempotency_key: str = Field(min_length=8, max_length=200, pattern=r"^[A-Za-z0-9_.:-]+$")
    messages: list[Message] = Field(min_length=1, max_length=MAX_SOURCE_MESSAGES)
    context_messages: list[Message] = Field(default_factory=list, max_length=MAX_SOURCE_MESSAGES)
    operation: Literal["organize", "political_filter"] = "organize"

    @model_validator(mode="after")
    def bounded_chunk(self):
        if self.operation == "political_filter":
            if self.context_messages:
                raise ValueError("片段筛查不接受前段参考")
            if len(self.messages) > MAX_FILTER_MESSAGES:
                raise ValueError(f"片段筛查每次最多 {MAX_FILTER_MESSAGES} 段")
            if sum(len(message.content) for message in self.messages) > MAX_FILTER_CHARACTERS:
                raise ValueError(f"片段筛查每次正文总计不能超过 {MAX_FILTER_CHARACTERS} 字符")
            if any(message.role != "user" for message in self.messages):
                raise ValueError("片段筛查必须使用 user 角色传递引用片段")
            if len({message.id for message in self.messages}) != len(self.messages):
                raise ValueError("片段筛查的每个片段必须有独立来源编号")
        messages = self.messages + self.context_messages
        if len(messages) > MAX_SOURCE_MESSAGES:
            raise ValueError(f"主段及参考消息合计不能超过 {MAX_SOURCE_MESSAGES} 条")
        if sum(len(message.content) for message in self.context_messages) > MAX_CONTEXT_CHARACTERS:
            raise ValueError(f"前段参考正文不能超过 {MAX_CONTEXT_CHARACTERS} 字符")
        if sum(len(message.content) for message in messages) > MAX_SOURCE_CHARACTERS:
            raise ValueError(f"每次整理的正文总计不能超过 {MAX_SOURCE_CHARACTERS} 字符，请分段提交")
        if any(not message.content.strip() or not message.id.strip() for message in messages):
            raise ValueError("消息编号及正文不能为空白")
        # A source may be split into multiple pieces with the same role.
        roles = {}
        for message in messages:
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


class AccountLoginRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    username: str = Field(min_length=1, max_length=256)
    password: str = Field(min_length=1, max_length=128, repr=False)


class AccountRegisterRequest(AccountLoginRequest):
    invitation_code: str | None = Field(default=None, max_length=128, repr=False)


class AdminConfigPatch(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)
    public_enabled: bool | None = None
    daily_limit: int | None = Field(default=None, ge=0, le=1_000_000)
    user_daily_limit: int | None = Field(default=None, ge=0, le=1_000_000)
    public_registration_limit: int | None = Field(default=None, ge=0, le=1_000_000)
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
    continuityCue: Literal["PREFERENCE", "CORRECTION", "SHARED_LANGUAGE", "OPEN_THREAD", "RESPONSE_STYLE"] | None = None


class GeneratedDocument(BaseModel):
    model_config = ConfigDict(extra="ignore", strict=True)
    entries: list[GeneratedEntry] = Field(max_length=40)
