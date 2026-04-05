import { createContext, useContext, useReducer } from "react";
import type { Dispatch, ReactNode } from "react";
import type {
  AuctionListing,
  BankState,
  CraftingNode,
  CraftingRecipe,
  CraftingSkill,
  CurrencyBalance,
  EquipmentSlotDef,
  ItemSummary,
  ShopState,
  TradeState,
} from "../types";

export interface InventoryState {
  inventory: ItemSummary[];
  equipment: Record<string, ItemSummary>;
  equipmentSlotDefs: EquipmentSlotDef[];
  shop: ShopState | null;
  craftingSkills: CraftingSkill[];
  craftingRecipes: CraftingRecipe[];
  craftingNodes: CraftingNode[];
  tradeState: TradeState | null;
  auctionListings: AuctionListing[];
  bankState: BankState | null;
  currencies: CurrencyBalance[];
}

export type InventoryAction =
  | { type: "SET_INVENTORY"; payload: ItemSummary[] }
  | { type: "SET_EQUIPMENT"; payload: Record<string, ItemSummary> }
  | { type: "SET_EQUIPMENT_SLOT_DEFS"; payload: EquipmentSlotDef[] }
  | { type: "SET_SHOP"; payload: ShopState | null }
  | { type: "SET_CRAFTING_SKILLS"; payload: CraftingSkill[] }
  | { type: "SET_CRAFTING_RECIPES"; payload: CraftingRecipe[] }
  | { type: "SET_CRAFTING_NODES"; payload: CraftingNode[] }
  | { type: "SET_TRADE_STATE"; payload: TradeState | null }
  | { type: "SET_AUCTION_LISTINGS"; payload: AuctionListing[] }
  | { type: "SET_BANK_STATE"; payload: BankState | null }
  | { type: "SET_CURRENCIES"; payload: CurrencyBalance[] }
  | { type: "RESET" };

const INITIAL_INVENTORY_STATE: InventoryState = {
  inventory: [],
  equipment: {},
  equipmentSlotDefs: [],
  shop: null,
  craftingSkills: [],
  craftingRecipes: [],
  craftingNodes: [],
  tradeState: null,
  auctionListings: [],
  bankState: null,
  currencies: [],
};

function inventoryReducer(state: InventoryState, action: InventoryAction): InventoryState {
  switch (action.type) {
    case "SET_INVENTORY":
      return { ...state, inventory: action.payload };
    case "SET_EQUIPMENT":
      return { ...state, equipment: action.payload };
    case "SET_EQUIPMENT_SLOT_DEFS":
      return { ...state, equipmentSlotDefs: action.payload };
    case "SET_SHOP":
      return { ...state, shop: action.payload };
    case "SET_CRAFTING_SKILLS":
      return { ...state, craftingSkills: action.payload };
    case "SET_CRAFTING_RECIPES":
      return { ...state, craftingRecipes: action.payload };
    case "SET_CRAFTING_NODES":
      return { ...state, craftingNodes: action.payload };
    case "SET_TRADE_STATE":
      return { ...state, tradeState: action.payload };
    case "SET_AUCTION_LISTINGS":
      return { ...state, auctionListings: action.payload };
    case "SET_BANK_STATE":
      return { ...state, bankState: action.payload };
    case "SET_CURRENCIES":
      return { ...state, currencies: action.payload };
    case "RESET":
      return INITIAL_INVENTORY_STATE;
  }
}

const InventoryStateContext = createContext<InventoryState>(INITIAL_INVENTORY_STATE);
const InventoryDispatchContext = createContext<Dispatch<InventoryAction>>(() => {});

export function InventoryProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(inventoryReducer, INITIAL_INVENTORY_STATE);
  return (
    <InventoryStateContext value={state}>
      <InventoryDispatchContext value={dispatch}>
        {children}
      </InventoryDispatchContext>
    </InventoryStateContext>
  );
}

export function useInventoryState(): InventoryState {
  return useContext(InventoryStateContext);
}

export function useInventoryDispatch(): Dispatch<InventoryAction> {
  return useContext(InventoryDispatchContext);
}
