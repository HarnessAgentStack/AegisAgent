/**
 * @file ESLint Flat Config
 * @description 基于 ESLint 9 flat config 的前端代码规范，覆盖 TS/TSX。
 *              规则目标：
 *                - 杜绝 `console.log` / `any` / 裸 `JSON.parse` / 隐式 `Number()` 精度陷阱；
 *                - 强制 import 顺序、注释规范、文件头、命名空间；
 *                - 门禁以 build 验证为主，lint 作为质量增强。
 * @author wang.zhen
 * @since 1.0.0
 */
import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import react from 'eslint-plugin-react';
import reactHooks from 'eslint-plugin-react-hooks';
import importPlugin from 'eslint-plugin-import';
import unusedImports from 'eslint-plugin-unused-imports';

/** 项目级 TS/TSX files 匹配 */
const PROFILED_FILES = ['src/**/*.{ts,tsx}'];

/** @type {import('eslint').Linter.FlatConfig[]} */
export default tseslint.config(
  // === 全局忽略 ===
  {
    ignores: [
      'dist/**',
      'node_modules/**',
      '*.config.js',
      '*.config.ts',
      'src/vite-env.d.ts',
      'src/**/*.d.ts',
    ],
  },

  // === JS/TS 基础规则 ===
  js.configs.recommended,
  ...tseslint.configs.recommended,

  // === TS 类型感知规则（必须放在基础规则之后、项目规则之前，且需 projectService）===
  {
    files: PROFILED_FILES,
    languageOptions: {
      parserOptions: {
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    rules: {
      '@typescript-eslint/no-unnecessary-condition': 'warn',
      '@typescript-eslint/no-non-null-assertion': 'warn',
    },
  },

  // === React 规则 ===
  {
    files: PROFILED_FILES,
    plugins: {
      react,
      'react-hooks': reactHooks,
    },
    languageOptions: {
      globals: { ...globals.browser, ...globals.es2022 },
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
        ecmaFeatures: { jsx: true },
        projectService: true,
        tsconfigRootDir: import.meta.dirname,
      },
    },
    settings: {
      react: { version: 'detect' },
    },
    rules: {
      ...react.configs.recommended.rules,
      ...react.configs['jsx-runtime'].rules,
      ...reactHooks.configs.recommended.rules,
      'react/react-in-jsx-scope': 'off',
      'react/prop-types': 'off',
      'react/display-name': 'off',
    },
  },

  // === Import 规则 ===
  {
    files: PROFILED_FILES,
    plugins: { import: importPlugin },
    languageOptions: {
      globals: { ...globals.browser, ...globals.es2022 },
    },
    rules: {
      'import/order': [
        'warn',
        {
          groups: ['builtin', 'external', 'internal', 'parent', 'sibling', 'index'],
          'newlines-between': 'always',
          pathGroups: [
            { pattern: 'react', group: 'external', position: 'before' },
            { pattern: '@/api/**', group: 'internal' },
            { pattern: '@/components/**', group: 'internal' },
            { pattern: '@/utils/**', group: 'internal' },
            { pattern: '@/types/**', group: 'internal' },
            { pattern: '@/stores/**', group: 'internal' },
            { pattern: '@/hooks/**', group: 'internal' },
            { pattern: '@/pages/**', group: 'internal' },
          ],
          'pathGroupsExcludedImportTypes': ['react'],
        },
      ],
      'import/no-cycle': 'warn',
      'import/no-duplicates': 'error',
    },
  },

  // === unused-imports ===
  {
    files: PROFILED_FILES,
    plugins: { 'unused-imports': unusedImports },
    rules: {
      '@typescript-eslint/no-unused-vars': 'off',
      'unused-imports/no-unused-imports': 'warn',
      'unused-imports/no-unused-vars': [
        'warn',
        { vars: 'all', varsIgnorePattern: '^_', argsIgnorePattern: '^_' },
      ],
    },
  },

  // === 项目级规则 ===
  {
    files: PROFILED_FILES,
    languageOptions: {
      globals: {
        ...globals.browser,
        ...globals.es2022,
        AbortController: 'readonly',
        URL: 'readonly',
        Blob: 'readonly',
        File: 'readonly',
      },
    },
    rules: {
      // --- 类型安全 ---
      '@typescript-eslint/no-explicit-any': 'warn',
      '@typescript-eslint/explicit-module-boundary-types': 'off',
      '@typescript-eslint/no-non-null-assertion': 'warn',
      '@typescript-eslint/consistent-type-assertions': [
        'warn',
        { assertionStyle: 'as', objectLiteralTypeAssertions: 'allow-as-parameter' },
      ],

      // --- 日志治理（T2.2） ---
      'no-console': ['warn', { allow: ['warn', 'error'] }],
      'no-debugger': 'error',

      // --- 反模式 ---
      'no-eval': 'error',
      'no-new-wrappers': 'error',
      'prefer-const': 'warn',
      'no-param-reassign': ['warn', { props: false }],
      'eqeqeq': ['warn', 'always', { null: 'ignore' }],

      // --- 错误处理 ---
      'no-promise-executor-return': 'error',
      'prefer-promise-reject-errors': 'warn',

      // --- 代码风格 ---
      'arrow-body-style': ['warn', 'as-needed'],
      'no-template-curly-in-string': 'warn',
    },
  },

  // === 严格约束：禁止裸 localStorage / JSON.parse ===
  {
    files: PROFILED_FILES,
    // storage.ts 是 localStorage 封装层，number.ts 是 safeJsonParse 实现层，自身需要直接使用底层 API
    ignores: ['src/utils/storage.ts', 'src/utils/number.ts'],
    rules: {
      'no-restricted-properties': [
        'warn',
        {
          object: 'localStorage',
          property: 'getItem',
          message: '请使用 @/utils/storage 的 storage.get/getRaw 统一入口',
        },
        {
          object: 'localStorage',
          property: 'setItem',
          message: '请使用 @/utils/storage 的 storage.set/setRaw 统一入口',
        },
        {
          object: 'localStorage',
          property: 'removeItem',
          message: '请使用 @/utils/storage 的 storage.remove 统一入口',
        },
        {
          object: 'localStorage',
          property: 'clear',
          message: '请使用 @/utils/storage 的 storage.clear 统一入口',
        },
        {
          object: 'JSON',
          property: 'parse',
          message: '请使用 @/utils/number 的 safeJsonParse 统一容错解析',
        },
      ],
    },
  },
);
