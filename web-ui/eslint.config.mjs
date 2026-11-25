import js from "@eslint/js";
import babelParser from "@babel/eslint-parser";
import tseslint from "typescript-eslint";
import react from "eslint-plugin-react";
import reactHooks from "eslint-plugin-react-hooks";
import prettierRecommended from "eslint-plugin-prettier/recommended";

const prettierRule = ["error", { endOfLine: "auto" }];

export default [
  { ignores: ["build/**", "dist/**", "node_modules/**"] },
  {
    // Explicit, not "detect": eslint-plugin-react 7.37.5 detects through context.getFilename,
    // which ESLint 10 removed ("contextOrFilename.getFilename is not a function").
    settings: { react: { version: "18.3" } },
  },
  {
    files: ["**/*.js", "**/*.jsx"],
    ...js.configs.recommended,
  },
  {
    files: ["**/*.js", "**/*.jsx"],
    ...prettierRecommended,
  },
  {
    files: ["**/*.js", "**/*.jsx"],
    plugins: { react },
    languageOptions: {
      parser: babelParser,
      parserOptions: {
        requireConfigFile: false,
        ecmaFeatures: { jsx: true },
        babelOptions: {
          presets: ["@babel/preset-env", "@babel/preset-react"],
        },
      },
    },
    rules: {
      "prettier/prettier": prettierRule,
      "react/prop-types": "off",
      "react/jsx-uses-react": "error",
      "react/jsx-uses-vars": "error",
      "react/react-in-jsx-scope": "error",
      "no-unused-vars": "off",
      "no-case-declarations": "off",
      "no-undef": "off",
      "no-prototype-builtins": "off",
      semi: ["error", "always"],
    },
  },
  ...[
    js.configs.recommended,
    ...tseslint.configs.recommended,
    react.configs.flat.recommended,
    prettierRecommended,
  ].map((c) => ({ ...c, files: ["**/*.ts", "**/*.tsx"] })),
  {
    files: ["**/*.ts", "**/*.tsx"],
    plugins: { "react-hooks": reactHooks },
    languageOptions: {
      parser: tseslint.parser,
      parserOptions: {
        tsconfigRootDir: import.meta.dirname,
        project: "./tsconfig.json",
        ecmaVersion: 2018,
        sourceType: "module",
      },
    },
    rules: {
      // The two rules react-hooks' recommended set held before v6 added the React Compiler rules.
      "react-hooks/rules-of-hooks": "error",
      "react-hooks/exhaustive-deps": "warn",
      "@typescript-eslint/explicit-module-boundary-types": "off",
      "@typescript-eslint/no-inferrable-types": "off",
      "@typescript-eslint/no-explicit-any": "off",
      "@typescript-eslint/ban-ts-comment": "off",
      "@typescript-eslint/no-empty-function": "off",
      "@typescript-eslint/no-namespace": "off",
      "no-inner-declarations": "off",
      "no-case-declarations": "off",
      "@typescript-eslint/no-non-null-assertion": "off",
      "@typescript-eslint/no-unused-vars": "off",
      semi: ["error", "always"],
      "prettier/prettier": prettierRule,
      // typescript-eslint 8 split ban-types; this keeps `{}` allowed as before.
      "@typescript-eslint/no-empty-object-type": [
        "error",
        { allowObjectTypes: "always" },
      ],
    },
  },
];
