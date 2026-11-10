/**
 * 样式门禁（modernize-ui-ux 设计系统）：业务页面只能使用设计令牌。
 * - 禁止十六进制颜色：改用 var(--ls-color-*) 或 UnoCSS 令牌类；
 * - 禁止间距类属性写裸 px：改用 var(--ls-space-*)。
 * RuoYi 上游页面（system、monitor、tool、demo、登录注册）保持原样以便合并上游，不在检查范围内。
 */
const spacing = [
  'margin', 'margin-top', 'margin-right', 'margin-bottom', 'margin-left', 'margin-inline', 'margin-block',
  'padding', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left', 'padding-inline', 'padding-block',
  'gap', 'row-gap', 'column-gap'
];

export default {
  customSyntax: 'postcss-scss',
  rules: { 'color-no-invalid-hex': true },
  overrides: [
    { files: ['**/*.vue'], customSyntax: 'postcss-html' },
    {
      files: ['src/views/**/*.{vue,scss,css}'],
      rules: {
        'color-no-hex': [true, { message: '业务页面不得硬编码颜色，请使用设计令牌 var(--ls-color-*)' }],
        'declaration-property-unit-disallowed-list': [
          Object.fromEntries(spacing.map((p) => [p, ['px']])),
          { message: '业务页面的间距不得使用裸 px，请使用设计令牌 var(--ls-space-*)' }
        ]
      }
    }
  ],
  ignoreFiles: [
    'dist/**',
    'node_modules/**',
    'src/views/system/**',
    'src/views/monitor/**',
    'src/views/tool/**',
    'src/views/demo/**',
    'src/views/redirect/**',
    'src/views/login.vue',
    'src/views/register.vue'
  ]
};
