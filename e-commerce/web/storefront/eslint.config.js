import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['build/', '.react-router/', 'app/api/*.d.ts', 'node_modules/'] },
  ...tseslint.configs.recommended,
  {
    rules: {
      // XSS: React escapes everything it renders; raw HTML is never allowed (design/16 §16.6).
      'no-restricted-syntax': ['error',
        { selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']", message: 'Raw HTML is banned (XSS).' },
        { selector: "CallExpression[callee.name='eval']", message: 'eval is banned (CSP has no unsafe-eval).' },
      ],
      'no-restricted-globals': ['error', { name: 'localStorage', message: 'Nothing sensitive is stored in the browser.' },
        { name: 'sessionStorage', message: 'Nothing sensitive is stored in the browser.' }],
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
);
