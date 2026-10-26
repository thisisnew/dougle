# Expert upstream audit and adoption gate

Audit date: 2026-10-25. Scope: four public upstream repositories, their pinned license/provenance, HolmesGPT model connectivity, Mac packaging and read-only enforcement. This is a static source audit, not an installation, successful model connection, security certification or completed E01–E50 evaluation.

## Decision

**Stock HolmesGPT is not a drop-in consumer of Dougle's existing Codex login or a Claude desktop login.** The inspected configuration constructs `DefaultLLM` and calls LiteLLM completion APIs; it does not invoke Dougle's authenticated Codex/Claude CLI executor. Direct OpenAI/Anthropic setup documents API keys. However, saying “Holmes always requires an API key” would be inaccurate: its pinned LiteLLM dependency contains a separate ChatGPT OAuth provider, and Holmes explicitly supports GitHub Copilot OAuth and cloud identity routes. These do not establish reuse of an existing desktop session.

Start adoption with the provenance/license originals now included under [third-party/expert-sources](../third-party/expert-sources/manifest.json), then adapt narrowly selected investigation procedures behind Dougle-owned tools. Hold full Holmes runtime bundling until the model adapter, permission boundary, cancellation and both Mac architectures are demonstrated. No upstream code or skills have been vendored by this audit.

## Exact source pins and license evidence

These are default-branch snapshots resolved through public GitHub on the audit date, not selected stable releases. Every evidence link below is commit-pinned.

| Repository | Full commit SHA | License and notices |
|---|---|---|
| HolmesGPT/holmesgpt | [`6a50c178e9c8099c8fa242cd76a9334155ee9f0f`](https://github.com/HolmesGPT/holmesgpt/commit/6a50c178e9c8099c8fa242cd76a9334155ee9f0f) | [Apache-2.0 LICENSE](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/LICENSE); copyright 2025 Robusta Dev LTD |
| aws-samples/sample-multi-account-agentic-incident-response | [`eadba8b51669293e628fde9d50dc500eece3eef0`](https://github.com/aws-samples/sample-multi-account-agentic-incident-response/commit/eadba8b51669293e628fde9d50dc500eece3eef0) | [MIT-0 LICENSE](https://github.com/aws-samples/sample-multi-account-agentic-incident-response/blob/eadba8b51669293e628fde9d50dc500eece3eef0/LICENSE), [NOTICE](https://github.com/aws-samples/sample-multi-account-agentic-incident-response/blob/eadba8b51669293e628fde9d50dc500eece3eef0/NOTICE), [THIRD_PARTY_LICENSES.md](https://github.com/aws-samples/sample-multi-account-agentic-incident-response/blob/eadba8b51669293e628fde9d50dc500eece3eef0/THIRD_PARTY_LICENSES.md); Amazon.com, Inc. or affiliates |
| obra/superpowers | [`2ce8aa15d05bd57213e6f746f953aafb7ded7a8b`](https://github.com/obra/superpowers/commit/2ce8aa15d05bd57213e6f746f953aafb7ded7a8b) | [MIT LICENSE](https://github.com/obra/superpowers/blob/2ce8aa15d05bd57213e6f746f953aafb7ded7a8b/LICENSE); copyright 2025 Jesse Vincent |
| itbench-hub/ITBench | [`e35270629b336ea3170c912edf3c4632eba877eb`](https://github.com/itbench-hub/ITBench/commit/e35270629b336ea3170c912edf3c4632eba877eb) | [Apache-2.0 LICENSE](https://github.com/itbench-hub/ITBench/blob/e35270629b336ea3170c912edf3c4632eba877eb/LICENSE); copyright contributors to the ITBench project |

All four recursive Git-tree responses had `truncated=false`. A case-insensitive basename search for LICENSE*, NOTICE*, COPYING* and THIRD_PARTY* found only LICENSE for Holmes, Superpowers and ITBench, and the three listed files for AWS. “No separate NOTICE found” refers to those pinned trees; it is not a claim that dependency distributions or inline file headers contain no notices.

The [manifest](../third-party/expert-sources/manifest.json) records raw pinned URLs, exact SHA-256 hashes and local original-license paths. All six original documents are retained unchanged, including AWS's attribution inventory. This is a provenance inventory, not a complete runtime SBOM.

Redistribution checklist derived from these originals:

- Apache-2.0: include the license, retain applicable source attribution, mark changed files, and preserve applicable NOTICE content if supplied with the adopted distribution. The license does not grant general trademark rights.
- MIT: retain Jesse Vincent's copyright and permission notice with copies or substantial portions, including adapted skill text when applicable.
- MIT-0: the root grant does not contain MIT's attribution condition; nevertheless retain the supplied AWS LICENSE, NOTICE and third-party inventory for traceability. MIT-0 is not a blanket license for dependencies.
- AWS's NOTICE says PetAdoptions is fetched at deploy time from a separately licensed repository, and third-party packages/images are not bundled by the sample. Its inventory explicitly requires checking the actual resolved versions. Do not replace that inventory with “everything is MIT-0.”
- Before any code/skill import, record the exact imported file set and hashes, inspect their headers and referenced assets, and inventory the packages actually redistributed. This audit does not approve model/service contracts or all transitive licenses.

## HolmesGPT: actual model and authentication path

### What the source does

[Config construction](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/config.py#L976) instantiates `DefaultLLM(model, api_key, api_base, api_version, args, ...)`. [LLM initialization and validation](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/core/llm.py#L398) call `litellm.get_llm_provider` and provider/environment validation. [Completion](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/core/llm.py#L837) sends messages, tools, stream settings, timeout and provider arguments to `litellm.completion`. This is a model API abstraction, not a subprocess wrapper around `codex exec`, the Codex desktop application, `claude`, or the Claude desktop application.

[OpenAI setup](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/docs/ai-providers/openai.md#L5) explicitly distinguishes the API-key route from a ChatGPT Plus subscription. [Anthropic setup](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/docs/ai-providers/anthropic.md#L5) uses `ANTHROPIC_API_KEY` or a supplied key. Merely choosing a Claude or GPT model name does not attach a desktop login.

The negative finding is scoped to the inspected model construction/completion paths, provider docs and custom-LLM example. It is not a claim that no external proxy or future plugin could bridge these systems.

### Important dependency-level exceptions

[pyproject.toml](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/pyproject.toml) and [poetry.lock](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/poetry.lock#L3027) pin LiteLLM **1.89.0**. Its GitHub tag resolves to **2978a92bdb26db9676b3a265ee09078aa252e258**, which was inspected read-only. The lock records wheel SHA-256 `63b33e2de386ab2a83fed7ed852c755e59d461a21b16c79fc17993f1b8c3d154`; no wheel was downloaded, installed or compared against the tag source.

| Route | Observed authentication behavior | Existing desktop-login verdict |
|---|---|---|
| Holmes direct OpenAI | Documented API key; LiteLLM provider API call | No automatic reuse shown |
| Holmes direct Anthropic | Documented API key; dependency also supports explicitly supplied bearer/OAuth credentials | No automatic Claude desktop/CLI credential discovery shown in inspected path |
| LiteLLM `chatgpt/` provider | Separate token cache and device-code login implemented in dependency | Potential keyless route, but not existing Codex login reuse; Holmes end-to-end behavior NOT_RUN |
| Holmes `github_copilot/` | Explicit Holmes validation exception and documented LiteLLM device OAuth | Separate GitHub authorization/subscription, not Codex/Claude login |
| Bedrock / Azure identity | AWS credential-chain/role support and Azure AD token branch | Separate cloud identity and entitlement, not desktop-session reuse |
| Custom LLM / compatible endpoint | Extension seam or operator-provided endpoint | Requires implementation/configuration and validation |

The LiteLLM [ChatGPT authenticator](https://github.com/BerriAI/litellm/blob/2978a92bdb26db9676b3a265ee09078aa252e258/litellm/llms/chatgpt/authenticator.py#L31) defaults to `~/.config/litellm/chatgpt/auth.json`, configurable through `CHATGPT_TOKEN_DIR` and `CHATGPT_AUTH_FILE`. It reads its own top-level access/refresh-token fields, refreshes them, or initiates device-code login. It does not default to Dougle's managed Codex profile, a Codex desktop store, or a macOS Keychain lookup. The [provider dispatch](https://github.com/BerriAI/litellm/blob/2978a92bdb26db9676b3a265ee09078aa252e258/litellm/litellm_core_utils/get_llm_provider_logic.py#L872) and [ChatGPT transformation](https://github.com/BerriAI/litellm/blob/2978a92bdb26db9676b3a265ee09078aa252e258/litellm/llms/chatgpt/chat/transformation.py#L16) actually invoke that authenticator. Consequently even provider resolution can cause authentication activity; a future “configuration check” must not casually instantiate this route.

This source supports a **candidate separate-login integration**, not a verified claim that an existing subscription/model works in Holmes. Token lifecycle, supported API parameters, tool-call normalization, streaming, service acceptance and account entitlement remain untested. Do not point these configurable paths at an existing credential store or copy tokens as an adoption shortcut. No such paths were read during this audit.

LiteLLM's [Anthropic credential handling](https://github.com/BerriAI/litellm/blob/2978a92bdb26db9676b3a265ee09078aa252e258/litellm/llms/anthropic/common_utils.py#L625) accepts `ANTHROPIC_AUTH_TOKEN` and contains OAuth-aware header handling. Its [environment validator](https://github.com/BerriAI/litellm/blob/2978a92bdb26db9676b3a265ee09078aa252e258/litellm/utils.py#L6450) recognizes that variable. This is explicit credential input support, not evidence of automatic reuse of a Claude desktop login, an implemented desktop OAuth flow, or authorized subscription reuse.

Holmes's [Copilot documentation](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/docs/ai-providers/github-copilot.md#L8) and [validation branch](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/core/llm.py#L526) explicitly support a separate OAuth device flow. [Bedrock validation](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/core/llm.py#L489) can use cloud credentials without a model-provider API key; [Azure completion](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/core/llm.py#L811) can pass an AD token. These exceptions are why the blocker is **existing-login compatibility**, not a universal API-key requirement.

### Viable integration choices

1. **Preferred first step: retain Dougle's authenticated CLI execution path and adapt selected investigation procedures.** Tools remain application-owned, with explicit schemas, source boundaries and evidence records. This does not reuse the Holmes engine and must not be represented as doing so.
2. **Holmes engine plus a custom model adapter:** implement the [LLM interface](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/core/llm.py#L347) against the existing supported executor protocol. The [custom example](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/examples/custom_llm.py) returns a canned response and fake token counts; it is an extension demonstration, not a working Codex/Claude adapter. Real tool-call IDs/results, continuation messages, streaming, errors, model binding, token/context limits and cancellation must be translated. Plain CLI answer text is insufficient.
3. **Holmes with separate provider identity:** direct API, approved cloud identity or a separately validated OAuth route. This changes the connection experience and requires explicit product decisions; it does not satisfy “use the already-connected desktop login” by itself.

## HolmesGPT: Mac packaging feasibility

[Package metadata](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/pyproject.toml) requires Python **>=3.10,<3.14**, exposes `holmes = holmes.main:run`, and uses Poetry. Required dependencies include LiteLLM/OpenAI, FastAPI/Uvicorn/Pydantic, Kubernetes, boto3, Azure identity, Google AI Platform, MCP 1.28.1, Supabase, Kafka, database drivers, jq and tree-sitter. Optional development and OpenTelemetry groups are separate, but numerous cloud/tool libraries are ordinary runtime dependencies.

[The lockfile](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/poetry.lock) contains native wheel entries for both Mac architectures for examples including confluent-kafka 2.14.2, jq 1.11.0, tiktoken 0.13.0 and tree-sitter 0.25.2. That is useful packaging evidence, not proof that the entire dependency closure freezes and executes on both CPUs.

The pinned [release workflow](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/.github/workflows/build-binaries-and-brew.yaml#L7):

- Builds Linux amd64, macOS arm64 and Windows amd64; **no Darwin x86_64 matrix entry**.
- Uses Python 3.11, Poetry and PyInstaller, with an extra macOS unixODBC installation step.
- Includes toolsets/prompts/skills and LiteLLM data, plus explicit hidden imports.
- Produces a zipped PyInstaller directory. It does not establish an Intel Mac artifact or successful integration with Dougle's signed app.

[Install docs](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/docs/installation/cli-installation.md) offer Homebrew, pipx, source and Docker. These are upstream installation options, not a ready-made zero-install Dougle package. Docker examples mount cloud/Kubernetes configuration; they must not be followed against user profiles for a feasibility test. The [Dockerfile](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/Dockerfile) includes external CLIs such as kubectl, ArgoCD and Helm and GNU utilities. Native Mac BSD command behavior and tool availability cannot be inferred from the Linux image.

**Packaging gate:** choose a deliberately minimal tool inventory; build isolated pinned bundles for x86_64 and arm64; account for every dynamic library and separately licensed binary; verify relocatable paths, missing-system-tool behavior, startup/size/RSS, signing, termination and updates. Neither CPU build, startup, size nor memory measurement was performed here. Upstream arm64 workflow support does not prove current release availability or Intel incompatibility of the Python source.

## Read-only safeguards: useful mechanisms, insufficient desktop boundary

| Source evidence | Mechanism present | Adoption implication |
|---|---|---|
| [Bash configuration](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/plugins/toolsets/bash/common/config.py), [default lists](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/plugins/toolsets/bash/common/default_lists.py) | CLI core allowlist, extended filesystem commands, configurable deny/allow lists; sudo/su hardcoded blocks in validator configuration | Read-only verbs can still read sensitive files or Kubernetes secrets under ambient permissions. Prefix permission is not a Workspace/root or data-access boundary. |
| [Validation](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/plugins/toolsets/bash/validation.py) | Parsed command/argument/redirection checks; unsafe constructs blocked or require approval depending on policy; unparsed syntax requires approval | More than a prompt-only safeguard, but still a configurable command policy rather than OS isolation. |
| [Bash invocation](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/plugins/toolsets/bash/bash_toolset.py#L145) | Requires approval for unknown prefixes; merges saved/session prefixes; invocation skips its validation branch when `context.user_approved` is true | The host must own that flag. A permissive approval response or broad prefix approval can expand execution authority. |
| [Approval tokens](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/utils/approval_tokens.py), [resume path](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/core/tool_calling_llm.py#L347) | Signed JWT binds tool-call ID, name and argument hash; pending resume verifies it; default TTL is 30 days | Useful anti-forgery binding. Token module alone does not provide Dougle's one-use approval consumption, Workspace/model binding or revocation policy. Do not equate the two approval contracts. |
| [Executor construction](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/config.py#L458) | `enable_all_toolsets_possible=True` can auto-enable usable toolsets that pass the tag filter | Disable auto-discovery/enablement for embedded adoption; permit only reviewed tools and controlled prerequisites. |
| [Subprocess implementation](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/holmes/plugins/toolsets/bash/common/bash.py#L22) | Shell execution uses /bin/bash, inherited process context, output capture and timeout; timeout kills the shell process | No per-Workspace filesystem/network sandbox or process-group termination is established by this helper. Descendant cleanup needs explicit tests. |
| [Memory-limit documentation](https://github.com/HolmesGPT/holmesgpt/blob/6a50c178e9c8099c8fa242cd76a9334155ee9f0f/docs/data-sources/tool-execution-safety.md) | Per-subprocess `ulimit -v`; documentation says macOS does not enforce the cap and fallback hides failure | Do not advertise a hard Mac memory cap. Aggregate memory and output/cancellation bounds need host enforcement. |

The upstream core list itself includes grep/head/tail/jq and kubectl read operations. Reading is enough to disclose data to the model. Disabling write commands therefore does not satisfy the separate requirement to prevent credential reads and unrelated filesystem/cluster access.

For Dougle, prefer structured read adapters over arbitrary shell access. Enforce registered-source roots, symlink handling, bounded queries/results, destination restrictions and least-privilege service roles in execution code. Keep caller identity/model/Workspace/evidence approval binding in Dougle; do not let retrieved skills or telemetry widen it. Provide child-process/environment isolation and hard cancellation before enabling shell or external MCP tools. These are integration gates, not claims that an exploitable upstream vulnerability was demonstrated.

## Other upstreams: concrete adoption candidates

### AWS multi-account incident response

Use the [frontend-triage procedure](https://github.com/aws-samples/sample-multi-account-agentic-incident-response/blob/eadba8b51669293e628fde9d50dc500eece3eef0/agents/skills/frontend-triage/SKILL.md) for explicit observation scope, joining an existing investigation and waiting for delegated work to reach a terminal state within a bounded interval. Use [report-standards](https://github.com/aws-samples/sample-multi-account-agentic-incident-response/blob/eadba8b51669293e628fde9d50dc500eece3eef0/agents/skills/report-standards/SKILL.md) to distinguish live observations from knowledge-only guidance and constrain confidence. [Untrusted-content handling](https://github.com/aws-samples/sample-multi-account-agentic-incident-response/blob/eadba8b51669293e628fde9d50dc500eece3eef0/agents/skills/untrusted-content-handling/SKILL.md) provides useful rules for treating logs, retrieved passages and peer findings as evidence rather than instructions.

The [README](https://github.com/aws-samples/sample-multi-account-agentic-incident-response/blob/eadba8b51669293e628fde9d50dc500eece3eef0/README.md) describes three AWS accounts, managed DevOps Agent spaces, MCP delegation and Strands agents on Bedrock AgentCore. Its workload-specific names, alarm thresholds, roles, SNS escalation and deployment/fault-injection paths are not generic desktop capabilities. Some workflows send messages or mutate infrastructure; an investigation-only product must not inherit those powers. Adapt the procedures to registered Dougle tools and fixtures; do not install the stack or import its skills unchanged. Published sample metrics are not Dougle evaluation results.

### Superpowers

[Systematic debugging](https://github.com/obra/superpowers/blob/2ce8aa15d05bd57213e6f746f953aafb7ded7a8b/skills/systematic-debugging/SKILL.md) is a useful root-cause-first procedure: reproduce, inspect changes, collect boundary evidence, form/test a hypothesis, then verify a fix. Its source also includes diagnostic instrumentation and shell/keychain examples, so importing the entire text as an automatically executable read-only policy is unsuitable. Translate it into investigation steps whose tools are explicitly available; remediation remains a separate authorized workflow. Retain MIT attribution for any copied/adapted substantial text. No hooks, installers or skills were installed.

### ITBench

[ITBench](https://github.com/itbench-hub/ITBench/blob/e35270629b336ea3170c912edf3c4632eba877eb/README.md) is principally an evaluation/scenario source for this task, not the runtime to put inside the customer app. [Package metadata](https://github.com/itbench-hub/ITBench/blob/e35270629b336ea3170c912edf3c4632eba877eb/pyproject.toml) requires Python >=3.13,<3.16 and dependencies including Ansible, AWX, boto3 and Kubernetes. [Kind setup](https://github.com/itbench-hub/ITBench/blob/e35270629b336ea3170c912edf3c4632eba877eb/clusters/kind/README.md) requires a container engine and Go, and describes Mac gateway limitations with Minikube recommended for broader Mac development scenarios.

Reuse scenario/ground-truth design in isolated synthetic evaluation fixtures. Any later actual harness run needs disposable infrastructure and separate fault-injection permissions. Keep ground truth outside agent-visible inputs. The Apache root license does not clear every workload image, external agent or deployment dependency.

## Adoption gates and current status

| Gate | Status | Concrete evidence needed next |
|---|---|---|
| Four exact upstream pins, root license originals, AWS notices and file hashes | DONE | Local provenance manifest and six originals, byte-hash verification |
| Exact selected skill/code import inventory and per-file attribution | NOT_STARTED | Approved file list, hashes, inline notices and adaptation diff |
| Existing Codex/Claude login usable by stock Holmes | NOT_SUPPORTED_BY_INSPECTED_PATH | A real supported adapter; no credential copying |
| Separate ChatGPT OAuth through pinned LiteLLM in Holmes | NOT_RUN | Isolated explicit login, tool calls, streaming, refresh/cancel/error behavior; no ambient profile access |
| Full Holmes native Mac bundle | BLOCKED | Intel build path plus both-CPU dependency/signing/startup/runtime evidence |
| Read-only data boundary and cancellation | BLOCKED | Executor allowlist, source/credential isolation, least-privilege roles, process-tree and budget tests |
| Complete transitive/runtime license inventory | NOT_DONE | Resolved artifact inventory and all applicable notices |
| E01–E50 and real-model evaluation | NOT_RUN by this audit | Fixture runs and recorded results; static audit is not a passing score |

## Method and limits

Public unauthenticated GitHub REST/raw HTTPS requests were made with curl; code was inspected as response text only. No upstream code/skill files were saved, no fetched code was executed, no credentials or user authentication stores were read, and no dependency installation, provider login, model call, deployment, Git commit or push was performed. Only this document and the dedicated third-party provenance/license files belong to this change.

License originals were added with apply_patch and validated against SHA-256 values calculated from the pinned raw responses. Links and file references were checked structurally. Source review is selective rather than a complete security/dependency audit; a dependency Git tag is not a verified reproduction of its distribution wheel. No application test suite or app rebuild is required for these documentation/license-only changes.
