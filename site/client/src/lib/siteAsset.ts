/**
 * Planta de Mineração: os ativos de marca são publicados com o build estático.
 * BASE_URL preserva tanto o preview local quanto o prefixo /mine-loader do Pages.
 */
const previewAssets: Record<string, string> = {
  "mineloader-mark.png": "/manus-storage/mineloader-mark_5291dad1.png",
  "mineloader-hero-basalt-bridge.jpg": "/manus-storage/mineloader-hero-basalt-bridge_3d92254c.jpg",
  "mineloader-contract-layers-v2.jpg": "/manus-storage/mineloader-contract-layers-v2_60ae21d4.jpg",
  "mineloader-runtime-assembly-v2.jpg": "/manus-storage/mineloader-runtime-assembly-v2_39721d47.jpg",
  "mineloader-build-beacon-v2.jpg": "/manus-storage/mineloader-build-beacon-v2_4fd0730d.jpg",
};

export function siteAsset(filename: string) {
  if (import.meta.env.BASE_URL === "/") return previewAssets[filename] ?? "";
  return `${import.meta.env.BASE_URL}assets/${filename}`;
}
