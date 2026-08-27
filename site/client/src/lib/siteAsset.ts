/**
 * Planta de Mineração: os ativos de marca são publicados com o build estático.
 * BASE_URL preserva tanto o preview local quanto o prefixo /mine-loader do Pages.
 */
export function siteAsset(filename: string) {
  return `${import.meta.env.BASE_URL}assets/${filename}`;
}
