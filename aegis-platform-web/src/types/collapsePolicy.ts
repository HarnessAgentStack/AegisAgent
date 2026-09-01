/**
 * @file 折叠策略类型与默认值
 * @description 统一管理"思考样式 / 工具折叠 / 精简模式"三档策略（对标 VSCode Copilot
 *   chat.agent.thinkingStyle / chat.agent.thinking.collapsedTools）。
 *   策略源自全局 store（持久化），由各事件项组件读取并决定默认展开态。
 *
 * @author Aegis
 * @since 4.1.0
 */

/** 思考样式三档 */
export type ThinkingStyle = 'collapsed' | 'collapsedPreview' | 'fixedScrolling';

/** 工具折叠档位 */
export type CollapsedTools = 'none' | 'all' | 'readOnly';

/** 折叠策略集合 */
export interface CollapsePolicy {
  /** 思考样式（默认 collapsedPreview） */
  thinkingStyle: ThinkingStyle;
  /** 工具折叠（默认 all 完成态 / none 运行态由组件按状态判定） */
  collapsedTools: CollapsedTools;
  /** 全局精简模式（隐藏工具图标/缩进，仅摘要） */
  compact: boolean;
}

/** 默认折叠策略 */
export const DEFAULT_COLLAPSE_POLICY: CollapsePolicy = {
  thinkingStyle: 'collapsedPreview',
  collapsedTools: 'all',
  compact: false,
};
