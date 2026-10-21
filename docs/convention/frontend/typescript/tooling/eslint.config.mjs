import js from '@eslint/js';
import { defineConfig } from 'eslint/config';
import prettier from 'eslint-config-prettier/flat';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

export default defineConfig([
  { ignores: ['dist/**', 'coverage/**', '.next/**'] },
  {
    files: ['**/*.{js,cjs,mjs,jsx,ts,cts,mts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
    ],
  },
  prettier,
]);
