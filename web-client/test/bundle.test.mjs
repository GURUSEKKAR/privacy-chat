import { test } from "node:test";
import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import ts from "typescript";
test("production JavaScript has no unresolvable static package imports", async () => {
  const assets = fileURLToPath(new URL("../dist/assets/", import.meta.url));
  const files = (await readdir(assets)).filter((name) => name.endsWith(".js"));
  assert.ok(files.length);
  for (const name of files) {
    const code = await readFile(assets + "/" + name, "utf8");
    const ast = ts.createSourceFile(
      name,
      code,
      ts.ScriptTarget.ESNext,
      true,
      ts.ScriptKind.JS,
    );
    for (const statement of ast.statements) {
      if (
        (ts.isImportDeclaration(statement) ||
          ts.isExportDeclaration(statement)) &&
        statement.moduleSpecifier &&
        ts.isStringLiteral(statement.moduleSpecifier)
      ) {
        const target = statement.moduleSpecifier.text;
        assert.ok(
          target.startsWith(".") || target.startsWith("/"),
          `Browser cannot resolve static import ${target} in ${name}`,
        );
      }
    }
  }
});
