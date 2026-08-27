/**
 * Planta de Mineração: marca volumétrica em basalto/cobre, usada em escala alta
 * para ancorar a identidade técnica sem competir com o conteúdo editorial.
 */
type BrandMarkProps = {
  className?: string;
};

import { siteAsset } from "@/lib/siteAsset";

export function BrandMark({ className = "" }: BrandMarkProps) {
  return (
    <img
      src={siteAsset("mineloader-mark.png")}
      alt="Símbolo MineLoader"
      className={`brand-mark ${className}`}
    />
  );
}
