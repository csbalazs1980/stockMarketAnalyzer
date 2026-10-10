/**
 * Mirrors com.stockmarketanalyzer.backend.instrument.web.dto.InstrumentOption.
 *
 * Hand-written until the generated client lands, like wealth.models.ts and dividend.models.ts.
 */

/**
 * An instrument reduced to what a picker needs: a label, an id to submit, and a currency to
 * prefill from. The full InstrumentResponse carries six more fields no dropdown reads, which is
 * the whole reason /options exists.
 */
export interface InstrumentOption {
  id: number;
  symbol: string;
  name: string;
  currency: string;
}

/** Mirrors com.stockmarketanalyzer.backend.instrument.domain.AssetType. */
export const ASSET_TYPES = [
  'STOCK',
  'ETF',
  'INDEX',
  'FUND',
  'BOND',
  'CRYPTO',
  'COMMODITY',
  'FX',
] as const;

export type AssetType = (typeof ASSET_TYPES)[number];

/** Mirrors InstrumentResponse - the full row the administration screen edits. */
export interface Instrument {
  id: number;
  symbol: string;
  name: string;
  exchange: string | null;
  currency: string;
  assetType: AssetType;
  sector: string | null;
  active: boolean;
  createdAt: string | null;
  updatedAt: string | null;
}

/** Mirrors InstrumentRequest. Used for both create and the full-replace PUT. */
export interface InstrumentRequest {
  symbol: string;
  name: string;
  exchange: string | null;
  currency: string;
  assetType: AssetType;
  sector: string | null;
  active: boolean;
}

/** Query of the paged listing; every filter is optional. */
export interface InstrumentQuery {
  search?: string | null;
  assetType?: AssetType | null;
  exchange?: string | null;
  active?: boolean | null;
  page?: number;
  size?: number;
  sort?: string;
}
