// Stylesheets are bundled by webpack (sass-loader, css-loader, style-loader); TypeScript 6 checks
// side-effect imports, so it needs to know the module exists.
declare module "*.scss";
