// Keep diagrams small: round coordinates, drop editor metadata. Keep ids and styles (Mermaid relies on them).
export default {
  multipass: true,
  floatPrecision: 1,
  plugins: [
    { name: 'preset-default', params: { overrides: { cleanupIds: false, inlineStyles: false, minifyStyles: false, convertShapeToPath: false } } },
  ],
};
