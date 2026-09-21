# 2026-09-20/21 会话归档：WorkBuddy 接入混元网关 + 上游风控排查

## 本轮主线

1. **WorkBuddy CLI 通道 11140 风控定位**（09-20~09-21）
   - 全矩阵排除：token 新旧 / uid / 两套官方 smali 契约（Z1 15 头 / T1 20+ 头）/ body 字段 /
     IP 透传 / 消息内容 / 模型 / 宿主与设备双出口
   - 定性：账号级 chat 端点风控（models API 同 token 200 正常、网页版正常、换 token/重授权无效）
   - 教训与方法论已沉淀进 HANDOFF 追加段
2. **豆包桌面协议 710022005**：同类上游风控（早上通、高频测试后全拒、网页版正常）；
   EOFException 根因=上游断流后 App 读空流；cookie 修复（换 WebView 新 cookie）治标
3. **novel-proofreader 方法论定稿**：用户自备凭证 + 标准 OpenAI 兼容协议 = 现有形态，无需改代码；
   封号主因是测试轰炸，真人低频 + 独立凭证风险低
4. **去私人捆绑**：两台设备清空我的 token（providers apiKey + workbuddy_tokens.xml），
   API 供应商残条移除；保留 custom_web 扫码入口
5. **WebPageModelSelector（patch34/35 泛化版）**：文心 4 子模型 + 华为云 7 模型展开到中转 tab；
   WorkBuddy 网页 DOM 契约已扒全（菜单/chip/行/标题选择器 + 16 模型名），
   但网页自动化发送卡在 React 防护（布局溢出 + state 同步），路线放弃
6. **设备终态**：供应商下拉无 WorkBuddy API 残条、CNB 回隐藏态（chat_model 切到 workbuddy-cn）、
   我的凭证零残留

## 文件

- `HANDOFF-2026-09-15.md`：全量交接（含 09-20 两段 + 09-21 四段追加）
- `scripts/WebPageModelSelector.java`：网页中继子模型选择器（patch34/35 产物）
- `scripts/WorkBuddyClient.java`：WorkBuddy token 自愈 helper（patch32b 配套）
- `scripts/patch32b_workbuddy_autologin.py`：token 自愈 smali 补丁
- `scripts/tmp_wb_risk_*.py`：11140 风控排查脚本（端点对照/真实 IP/T1 契约）
- `scripts/tmp_fix_doubao_cookie.py`：豆包 cookie 修复（CDP 提取 → 写设备）
- `scripts/tmp_hide_cnb.py` / `tmp_rm_wb_final.py` / `tmp_clear_my_token.py` / `tmp_fix_chatmodel.py`：
  设备清理脚本（隐藏 CNB / 移除残条 / 清我的凭证 / 切换选中模型）

## 下一轮入口

HANDOFF「追加二/三/四/五」：登录引导三处改动（无 token → 自动跳登录页 → 扫码 →
点账号 → 自动换各人 token），做完即「人人扫码 + 16 模型出话」闭环。
