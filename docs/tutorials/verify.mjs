/**
 * Verifica a estrutura mínima dos tutoriais publicados sem depender de pacote
 * externo. É uma proteção para a fonte que o site consome diretamente da main.
 */
import { readFile } from "node:fs/promises";

const root = new URL("./", import.meta.url);
const index = JSON.parse(await readFile(new URL("index.json", root), "utf8"));
const requiredDocumentKeys = ["schema", "kind", "id", "index", "eyebrow", "title", "summary", "updated_at", "sources", "contracts", "outcome", "evidence", "sections"];
const expectedIds = ["bloco", "item", "ui", "lua"];
const failures = [];

function check(condition, message) {
  if (!condition) failures.push(message);
}

check(index.schema === 1, "index.json precisa declarar schema: 1");
check(index.kind === "mine_loader_tutorial_index", "index.json precisa declarar o kind canónico");
check(Array.isArray(index.items) && index.items.length === expectedIds.length, "index.json precisa listar os quatro tutoriais");

const seen = new Set();
for (const item of index.items ?? []) {
  check(typeof item.id === "string" && expectedIds.includes(item.id), `id inválido no índice: ${item.id}`);
  check(!seen.has(item.id), `id duplicado no índice: ${item.id}`);
  seen.add(item.id);
  check(typeof item.document === "string" && item.document.startsWith("docs/tutorials/"), `documento inválido para ${item.id}`);
  const filename = item.document?.replace("docs/tutorials/", "");
  try {
    const document = JSON.parse(await readFile(new URL(filename, root), "utf8"));
    for (const key of requiredDocumentKeys) check(key in document, `${filename} não declara ${key}`);
    check(document.schema === 1, `${filename} precisa declarar schema: 1`);
    check(document.kind === "mine_loader_tutorial", `${filename} precisa declarar kind canónico`);
    check(document.id === item.id, `${filename} não corresponde ao id do índice`);
    check(Array.isArray(document.sections) && document.sections.length > 0, `${filename} precisa conter seções`);
    for (const section of document.sections ?? []) {
      check(["split", "code", "table"].includes(section.layout), `${filename} possui layout inválido em ${section.id}`);
      if (section.layout === "table") check(Array.isArray(section.table?.headers) && Array.isArray(section.table?.rows), `${filename} precisa de tabela em ${section.id}`);
      if (["split", "code"].includes(section.layout)) check(typeof section.code?.value === "string", `${filename} precisa de código em ${section.id}`);
    }
  } catch (error) {
    failures.push(`não foi possível ler ${filename}: ${error instanceof Error ? error.message : String(error)}`);
  }
}

for (const id of expectedIds) check(seen.has(id), `o índice não contém ${id}`);
if (failures.length) {
  console.error("Tutoriais JSON inválidos:\n- " + failures.join("\n- "));
  process.exit(1);
}
console.log(`Tutoriais JSON válidos: ${index.items.length}/${expectedIds.length}`);
