import { COMPASS_DIRECTIONS, type Direction } from "../constants";

export function isDirection(value: string): value is Direction {
  return (COMPASS_DIRECTIONS as readonly string[]).includes(value);
}

