import { defineTool } from "@deepseek-ai/dsh-tools";
//#region lib/types/index.js
/**
* Deterministic progressive routing for an agent preset.
*
* The first human message selects a small model-facing tool set. The selection
* is derived from durable session state on every prompt assembly, so a later
* request does not silently widen the catalog or require a second LLM router.
*
* @module @deepseek-ai/dsh-router-progressive
*/
/** Cordis plugin name. */
const name = "router-progressive";
/** Services used by the scoped router row. */
const inject = ["systemPrompt", "tools"];
const REACT_RE = /(开发|创建|写一个|生成|从零|做一个|网站|网页|构建|新项目|实现|做出|上线|落地|脚本|应用|build|create|develop|generate|implement|make a|new project)/i;
const SPEC_RE = /(修复|修一个|调试|重构|维护|排查|报错|出错|崩溃|优化|审查|review|fix|debug|refactor|maintain|repair|broken|迁移|升级|兼容)/i;
/** Tools present for every routed standard-plus agent. */
const CORE_TOOLS = [
	"read",
	"edit",
	"write",
	"glob",
	"grep",
	"pwsh",
	"dev_router_status",
	"dev_router_mode"
];
const CAPABILITIES = [
	{
		id: "web",
		test: /(今天|最新|价格|网页|网址|搜索|浏览|新闻|http:\/\/|https:\/\/|web|search)/i,
		tools: ["web_search", "web_fetch"],
		label: "命中：网页、搜索或最新信息"
	},
	{
		id: "jobs",
		test: /(后台运行|长时间任务|持续执行|稍后读取|background|long-running|job|定时)/i,
		tools: [
			"job_output",
			"job_list",
			"job_kill"
		],
		label: "命中：后台或长时间任务"
	},
	{
		id: "skills",
		test: /(技能|能力包|说明|skill)/i,
		tools: ["skill"],
		label: "命中：skill 或能力包"
	},
	{
		id: "planning",
		test: /(计划|规划|方案|先分析再做|设计方案|plan)/i,
		tools: ["exit_plan_mode"],
		label: "命中：计划、方案或设计"
	},
	{
		id: "subagent",
		test: /(并行调查|独立上下文|复杂分工|子任务|子代理|subagent|delegate|delegation)/i,
		tools: [
			"subagent",
			"subagent_fork",
			"subagent_codex",
			"subagent_claude_code",
			"list_agents",
			"send_message",
			"interrupt_agent"
		],
		label: "命中：并行分工或 subagent"
	},
	{
		id: "workflow",
		test: /(工作流|多阶段编排|multi-agent orchestration|workflow)/i,
		tools: ["workflow"],
		label: "命中：workflow 或多阶段编排"
	},
	{
		id: "goal",
		test: /(长期目标|持续跟踪|目标管理|goal)/i,
		tools: [
			"get_goal",
			"create_goal",
			"update_goal"
		],
		label: "命中：goal 或持续目标"
	},
	{
		id: "interaction",
		test: /(先问我|需要我选择|确认选项|ask_user)/i,
		tools: ["ask_user_question"],
		label: "命中：用户选择或确认"
	},
	{
		id: "todo",
		test: /(待办|任务清单|todo)/i,
		tools: ["todo_write"],
		label: "命中：待办或 todo"
	}
];
const MANAGED_TOOLS = new Set([...CORE_TOOLS, ...CAPABILITIES.flatMap((capability) => capability.tools)]);
const overrides = /* @__PURE__ */ new Map();
/** Clamp a numeric mode to the supported 0..1 range. */
function clampMode(value) {
	return Math.min(1, Math.max(0, value));
}
/** Convert a mode into the user-facing band used in prompt diagnostics. */
function bandOf(mode) {
	if (mode === "weak") return "weak";
	if (mode < .2) return "spec";
	if (mode < .5) return "transition";
	return "react";
}
/** Classify build/fix intent without making a model request. */
function classifyTask(text) {
	const react = REACT_RE.test(text) ? 1 : 0;
	const spec = SPEC_RE.test(text) ? 1 : 0;
	if (react > spec) return 1;
	if (spec > react) return 0;
	return "weak";
}
/** Extract text blocks from a durable user message. */
function extractText(message) {
	return message?.content.map((block) => block.type === "text" ? block.text : "").join(" ") ?? "";
}
/** Return the first direct human message, excluding injected plugin context. */
function firstUserText(session) {
	for (const candidate of session.events) {
		if (candidate.type === "user/message" && candidate.data.source.kind === "user") return extractText(candidate.data);
		if (candidate.type === "agent/inbox/spliced" && candidate.data.outcome !== "canceled") {
			const message = candidate.data.inserted.find((item) => item.source.kind === "user");
			if (message !== void 0) return extractText(message);
		}
	}
	return "";
}
/** Build the deterministic capability decision for one first-turn text. */
function routeFor(text) {
	const capabilities = [];
	const tools = [];
	const reasons = [];
	for (const capability of CAPABILITIES) {
		if (!capability.test.test(text)) continue;
		capabilities.push(capability.id);
		tools.push(...capability.tools);
		reasons.push(capability.label);
	}
	return {
		mode: classifyTask(text),
		capabilities,
		tools: [...new Set(tools)],
		reason: reasons.join("；") || "未命中能力关键词，保持核心工具集",
		confidence: "heuristic"
	};
}
/** Return the progressive model-facing tool set for a route. */
function progressiveToolsFor(route) {
	return new Set([...CORE_TOOLS, ...route.tools]);
}
/** Parse a user-facing mode override. */
function parseMode(value) {
	if (value === void 0 || value === null) return null;
	const token = String(value).trim().toLowerCase();
	if (token === "auto") return "auto";
	if (token === "weak" || token === "router") return "weak";
	if (token === "spec" || token === "spec-lean") return 0;
	if (token === "balanced" || token === "mixed") return .3;
	if (token === "react" || token === "react-lean") return 1;
	const number = Number(token);
	if (!Number.isFinite(number)) return null;
	return clampMode(token.includes(".") ? number : number / 100);
}
function modeFor(agent) {
	return overrides.get(String(agent.session.id)) ?? routeFor(firstUserText(agent.session)).mode;
}
function allowedToolsFor(agent) {
	return progressiveToolsFor(routeFor(firstUserText(agent.session)));
}
function routeNote(route, mode) {
	return `路由：mode=${bandOf(mode)}；reason=${route.reason}；capabilities=${route.capabilities.join(",") || "core"}；confidence=${route.confidence}`;
}
function routeGuidance(mode) {
	if (bandOf(mode) !== "weak") return "";
	return "路由提示：先判断这是“直接制作”还是“检查修复”；制作类任务直接落地并验证，修复类任务先检查再修改。";
}
function routeToolGuard(execution) {
	if (execution.agent === void 0 || !MANAGED_TOOLS.has(execution.name)) return void 0;
	return allowedToolsFor(execution.agent).has(execution.name) ? void 0 : `progressive router：工具“${execution.name}”未被当前首条用户请求选中`;
}
function statusText(agent) {
	const route = routeFor(firstUserText(agent.session));
	const mode = modeFor(agent);
	const selected = [...allowedToolsFor(agent)].join(", ");
	return [
		`mode=${mode} (band=${bandOf(mode)})`,
		`reason=${route.reason}`,
		`confidence=${route.confidence}`,
		`capabilities=[${route.capabilities.join(", ")}]`,
		`selected=[${selected}]`,
		`override=${overrides.has(String(agent.session.id)) ? "yes" : "no"}`
	].join("\n");
}
/** Install the progressive router in an agent-preset scope. */
function apply(ctx) {
	ctx.on("system-prompt/assemble", async (_assembly, context, next) => {
		const agent = context.agent;
		if (agent === void 0) return next();
		const route = routeFor(firstUserText(agent.session));
		const mode = modeFor(agent);
		const allowed = allowedToolsFor(agent);
		const transformed = await next();
		const sections = transformed.sections.filter((section) => !section.name.startsWith("tool:") || allowed.has(section.name.slice(5))).filter((section) => section.name !== "router:route" && section.name !== "router:guidance");
		sections.push({
			name: "router:route",
			text: routeNote(route, mode)
		});
		const guidance = routeGuidance(mode);
		if (guidance !== "") sections.push({
			name: "router:guidance",
			text: guidance
		});
		return {
			...transformed,
			sections,
			tools: transformed.tools.filter((tool) => tool.name === "run_code" || allowed.has(tool.name))
		};
	});
	ctx.tools.guard(routeToolGuard);
	ctx.on("session/disposed", (session) => {
		overrides.delete(String(session.id));
	});
	ctx.tools.register(defineTool({
		name: "dev_router_status",
		description: "显示当前会话的确定性路由模式、原因、能力包和选中的工具。",
		parameters: {},
		output: {
			schema: { type: "string" },
			render: (_args, value) => [{
				type: "text",
				text: value
			}]
		},
		execute: async (_args, execution) => execution.agent === void 0 ? "no agent session" : statusText(execution.agent)
	}));
	ctx.tools.register(defineTool({
		name: "dev_router_mode",
		description: "设置本会话下一轮使用的路由模式：auto / weak / spec / balanced / react / 0-100。",
		parameters: { mode: {
			type: "string",
			required: true,
			description: "auto / weak / spec / balanced / react / 0-100"
		} },
		output: {
			schema: { type: "string" },
			render: (_args, value) => [{
				type: "text",
				text: value
			}]
		},
		execute: async (args, execution) => {
			if (execution.agent === void 0) return "no agent session";
			const parsed = parseMode(args.mode);
			if (parsed === null) return `invalid mode "${args.mode}"`;
			const id = String(execution.agent.session.id);
			if (parsed === "auto") overrides.delete(id);
			else overrides.set(id, parsed);
			const current = modeFor(execution.agent);
			return `mode=${current} (band=${bandOf(current)})；下一轮请求生效`;
		}
	}));
}
var types_default = Object.assign(apply, { inject });
//#endregion
export { CORE_TOOLS, apply, bandOf, clampMode, classifyTask, types_default as default, extractText, firstUserText, inject, name, parseMode, progressiveToolsFor, routeFor };

