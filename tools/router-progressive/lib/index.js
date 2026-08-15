import { defineTool } from "@deepseek-ai/dsh-tools";
const name = "router-progressive";
const inject = ["systemPrompt", "tools"];
const REACT_RE = /(开发|创建|写一个|生成|从零|做一个|网站|网页|构建|新项目|实现|做出|上线|落地|脚本|应用|build|create|develop|generate|implement|make a|new project)/i;
const SPEC_RE = /(修复|修一个|调试|重构|维护|排查|报错|出错|崩溃|优化|审查|review|fix|debug|refactor|maintain|repair|broken|迁移|升级|兼容)/i;
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
  { id: "web", test: /(今天|最新|价格|网页|网址|搜索|浏览|新闻|http:\/\/|https:\/\/|web|search)/i, tools: ["web_search", "web_fetch"], label: "\u547D\u4E2D\uFF1A\u7F51\u9875\u3001\u641C\u7D22\u6216\u6700\u65B0\u4FE1\u606F" },
  { id: "jobs", test: /(后台运行|长时间任务|持续执行|稍后读取|background|long-running|job|定时)/i, tools: ["job_output", "job_list", "job_kill"], label: "\u547D\u4E2D\uFF1A\u540E\u53F0\u6216\u957F\u65F6\u95F4\u4EFB\u52A1" },
  { id: "skills", test: /(技能|能力包|说明|skill)/i, tools: ["skill"], label: "\u547D\u4E2D\uFF1Askill \u6216\u80FD\u529B\u5305" },
  { id: "planning", test: /(计划|规划|方案|先分析再做|设计方案|plan)/i, tools: ["exit_plan_mode"], label: "\u547D\u4E2D\uFF1A\u8BA1\u5212\u3001\u65B9\u6848\u6216\u8BBE\u8BA1" },
  { id: "subagent", test: /(并行调查|独立上下文|复杂分工|子任务|子代理|subagent|delegate|delegation)/i, tools: ["subagent", "subagent_fork", "subagent_codex", "subagent_claude_code", "list_agents", "send_message", "interrupt_agent"], label: "\u547D\u4E2D\uFF1A\u5E76\u884C\u5206\u5DE5\u6216 subagent" },
  { id: "workflow", test: /(工作流|多阶段编排|multi-agent orchestration|workflow)/i, tools: ["workflow"], label: "\u547D\u4E2D\uFF1Aworkflow \u6216\u591A\u9636\u6BB5\u7F16\u6392" },
  { id: "goal", test: /(长期目标|持续跟踪|目标管理|goal)/i, tools: ["get_goal", "create_goal", "update_goal"], label: "\u547D\u4E2D\uFF1Agoal \u6216\u6301\u7EED\u76EE\u6807" },
  { id: "interaction", test: /(先问我|需要我选择|确认选项|ask_user)/i, tools: ["ask_user_question"], label: "\u547D\u4E2D\uFF1A\u7528\u6237\u9009\u62E9\u6216\u786E\u8BA4" },
  { id: "todo", test: /(待办|任务清单|todo)/i, tools: ["todo_write"], label: "\u547D\u4E2D\uFF1A\u5F85\u529E\u6216 todo" }
];
const MANAGED_TOOLS = /* @__PURE__ */ new Set([
  ...CORE_TOOLS,
  ...CAPABILITIES.flatMap((capability) => capability.tools)
]);
const WEB_FETCH_FOLLOW_UP_SENTENCE = "Follow up with web_fetch when you need the full content of a specific result, and cite the relevant URLs as markdown links.";
const WEB_FETCH_TOKEN_RE = /\bweb_fetch\b/g;
const overrides = /* @__PURE__ */ new Map();
function clampMode(value) {
  return Math.min(1, Math.max(0, value));
}
function bandOf(mode) {
  if (mode === "weak") return "weak";
  if (mode < 0.2) return "spec";
  if (mode < 0.5) return "transition";
  return "react";
}
function classifyTask(text) {
  const react = REACT_RE.test(text) ? 1 : 0;
  const spec = SPEC_RE.test(text) ? 1 : 0;
  if (react > spec) return 1;
  if (spec > react) return 0;
  return "weak";
}
function extractText(message) {
  return message?.content.map((block) => block.type === "text" ? block.text : "").join(" ") ?? "";
}
function firstUserText(session) {
  for (const candidate of session.events) {
    if (candidate.type === "user/message" && candidate.data.source.kind === "user") {
      return extractText(candidate.data);
    }
    if (candidate.type === "agent/inbox/spliced" && candidate.data.outcome !== "canceled") {
      const message = candidate.data.inserted.find((item) => item.source.kind === "user");
      if (message !== void 0) return extractText(message);
    }
  }
  return "";
}
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
    reason: reasons.join("\uFF1B") || "\u672A\u547D\u4E2D\u80FD\u529B\u5173\u952E\u8BCD\uFF0C\u4FDD\u6301\u6838\u5FC3\u5DE5\u5177\u96C6",
    confidence: "heuristic"
  };
}
function progressiveToolsFor(route) {
  return /* @__PURE__ */ new Set([...CORE_TOOLS, ...route.tools]);
}
function parseMode(value) {
  if (value === void 0 || value === null) return null;
  const token = String(value).trim().toLowerCase();
  if (token === "auto") return "auto";
  if (token === "weak" || token === "router") return "weak";
  if (token === "spec" || token === "spec-lean") return 0;
  if (token === "balanced" || token === "mixed") return 0.3;
  if (token === "react" || token === "react-lean") return 1;
  const number = Number(token);
  if (!Number.isFinite(number)) return null;
  return clampMode(token.includes(".") ? number : number / 100);
}
function modeFor(agent) {
  return overrides.get(String(agent.session.id)) ?? routeFor(firstUserText(agent.session)).mode;
}
function allowedToolsFor(agent, web) {
  const route = routeFor(firstUserText(agent.session));
  const allowed = progressiveToolsFor(route);
  if (web !== void 0 && route.capabilities.includes("web")) {
    if (!web.isAvailable("search")) allowed.delete("web_search");
    if (!web.isAvailable("fetch")) allowed.delete("web_fetch");
  }
  return allowed;
}
function sanitizeUnavailableToolMentions(text, allowed) {
  if (allowed.has("web_fetch")) return text;
  return text.replace(WEB_FETCH_FOLLOW_UP_SENTENCE, "Use the returned source snippets when available, and cite the relevant URLs as markdown links.").replace(WEB_FETCH_TOKEN_RE, "the returned source snippets");
}
function routeNote(route, mode) {
  return `\u8DEF\u7531\uFF1Amode=${bandOf(mode)}\uFF1Breason=${route.reason}\uFF1Bcapabilities=${route.capabilities.join(",") || "core"}\uFF1Bconfidence=${route.confidence}`;
}
function routeGuidance(mode) {
  if (bandOf(mode) !== "weak") return "";
  return "\u8DEF\u7531\u63D0\u793A\uFF1A\u5148\u5224\u65AD\u8FD9\u662F\u201C\u76F4\u63A5\u5236\u4F5C\u201D\u8FD8\u662F\u201C\u68C0\u67E5\u4FEE\u590D\u201D\uFF1B\u5236\u4F5C\u7C7B\u4EFB\u52A1\u76F4\u63A5\u843D\u5730\u5E76\u9A8C\u8BC1\uFF0C\u4FEE\u590D\u7C7B\u4EFB\u52A1\u5148\u68C0\u67E5\u518D\u4FEE\u6539\u3002";
}
function routeToolGuard(execution, web) {
  if (execution.agent === void 0 || !MANAGED_TOOLS.has(execution.name)) return void 0;
  const allowed = allowedToolsFor(execution.agent, web);
  return allowed.has(execution.name) ? void 0 : `progressive router\uFF1A\u5DE5\u5177\u201C${execution.name}\u201D\u672A\u88AB\u5F53\u524D\u9996\u6761\u7528\u6237\u8BF7\u6C42\u9009\u4E2D`;
}
function statusText(agent, web) {
  const route = routeFor(firstUserText(agent.session));
  const mode = modeFor(agent);
  const selected = [...allowedToolsFor(agent, web)].join(", ");
  return [
    `mode=${mode} (band=${bandOf(mode)})`,
    `reason=${route.reason}`,
    `confidence=${route.confidence}`,
    `capabilities=[${route.capabilities.join(", ")}]`,
    `selected=[${selected}]`,
    `override=${overrides.has(String(agent.session.id)) ? "yes" : "no"}`
  ].join("\n");
}
function apply(ctx) {
  const web = ctx.get("web");
  ctx.on("system-prompt/assemble", async (_assembly, context, next) => {
    const agent = context.agent;
    if (agent === void 0) return next();
    const route = routeFor(firstUserText(agent.session));
    const mode = modeFor(agent);
    const transformed = await next();
    const composedNames = new Set(transformed.tools.map((tool) => tool.name));
    const allowed = new Set([...allowedToolsFor(agent, web)].filter((name2) => composedNames.has(name2)));
    const sections = transformed.sections.filter((section) => !section.name.startsWith("tool:") || allowed.has(section.name.slice("tool:".length))).filter((section) => section.name !== "router:route" && section.name !== "router:guidance").map((section) => ({
      ...section,
      text: sanitizeUnavailableToolMentions(section.text, allowed)
    }));
    sections.push({ name: "router:route", text: routeNote(route, mode) });
    const guidance = routeGuidance(mode);
    if (guidance !== "") sections.push({ name: "router:guidance", text: guidance });
    return {
      ...transformed,
      sections,
      tools: transformed.tools.filter((tool) => tool.name === "run_code" || allowed.has(tool.name))
    };
  });
  ctx.tools.guard((execution) => routeToolGuard(execution, web));
  ctx.on("session/disposed", (session) => {
    overrides.delete(String(session.id));
  });
  ctx.tools.register(defineTool({
    name: "dev_router_status",
    description: "\u663E\u793A\u5F53\u524D\u4F1A\u8BDD\u7684\u786E\u5B9A\u6027\u8DEF\u7531\u6A21\u5F0F\u3001\u539F\u56E0\u3001\u80FD\u529B\u5305\u548C\u9009\u4E2D\u7684\u5DE5\u5177\u3002",
    parameters: {},
    output: {
      schema: { type: "string" },
      render: (_args, value) => [{ type: "text", text: value }]
    },
    execute: async (_args, execution) => execution.agent === void 0 ? "no agent session" : statusText(execution.agent, web)
  }));
  ctx.tools.register(defineTool({
    name: "dev_router_mode",
    description: "\u8BBE\u7F6E\u672C\u4F1A\u8BDD\u4E0B\u4E00\u8F6E\u4F7F\u7528\u7684\u8DEF\u7531\u6A21\u5F0F\uFF1Aauto / weak / spec / balanced / react / 0-100\u3002",
    parameters: {
      mode: { type: "string", required: true, description: "auto / weak / spec / balanced / react / 0-100" }
    },
    output: {
      schema: { type: "string" },
      render: (_args, value) => [{ type: "text", text: value }]
    },
    execute: async (args, execution) => {
      if (execution.agent === void 0) return "no agent session";
      const parsed = parseMode(args.mode);
      if (parsed === null) return `invalid mode "${args.mode}"`;
      const id = String(execution.agent.session.id);
      if (parsed === "auto") overrides.delete(id);
      else overrides.set(id, parsed);
      const current = modeFor(execution.agent);
      return `mode=${current} (band=${bandOf(current)})\uFF1B\u4E0B\u4E00\u8F6E\u8BF7\u6C42\u751F\u6548`;
    }
  }));
}
var index_default = Object.assign(apply, { inject });
export {
  CORE_TOOLS,
  apply,
  bandOf,
  clampMode,
  classifyTask,
  index_default as default,
  extractText,
  firstUserText,
  inject,
  name,
  parseMode,
  progressiveToolsFor,
  routeFor
};
