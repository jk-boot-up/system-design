// Generates TypeScript types from each service's OpenAPI contract into app/api/. The services are contract-first, so a
// breaking change to a contract fails `npm run typecheck` here (design/16 §16.11). Run after a contract changes.
import { writeFile } from 'node:fs/promises';
import openapiTS, { astToString } from 'openapi-typescript';

const services = ['catalog', 'search', 'cart', 'order', 'review'] as const;
for (const name of services) {
  const source = new URL(`../../../services/${name}-service/contracts/openapi.yaml`, import.meta.url);
  const ast = await openapiTS(source, { alphabetize: true });
  const header = `// Generated from services/${name}-service/contracts/openapi.yaml by scripts/api-types.ts. Do not edit.\n`;
  await writeFile(new URL(`../app/api/${name}.d.ts`, import.meta.url), header + astToString(ast));
  console.log(`app/api/${name}.d.ts`);
}
