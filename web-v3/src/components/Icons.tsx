import type { Direction } from "../constants";
import { percent } from "../utils";

export function Bar({
  label,
  value,
  max,
  text,
  tone,
}: {
  label: string;
  value: number;
  max: number;
  text: string;
  tone: "hp" | "mana" | "xp";
}) {
  const width = percent(value, max);
  return (
    <div className="meter-block">
      <div className="meter-label-row">
        <span>{label}</span>
        <span>{text}</span>
      </div>
      <div className="meter-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={width}>
        <span className={`meter-fill meter-fill-${tone}`} style={{ width: `${width}%` }} />
      </div>
    </div>
  );
}

export function DirectionIcon({ direction, className }: { direction: Direction; className?: string }) {
  switch (direction) {
    case "north":
      return (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
          <path d="M12 19V7" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M8.6 10.4 12 7l3.4 3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M9 6.7c.9-.6 1.9-.9 3-.9s2.1.3 3 .9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
        </svg>
      );
    case "east":
      return (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
          <path d="M5 12h12" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M13.6 8.6 17 12l-3.4 3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M17.3 9c.6.9.9 1.9.9 3s-.3 2.1-.9 3" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
        </svg>
      );
    case "south":
      return (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
          <path d="M12 5v12" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M15.4 13.6 12 17l-3.4-3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M15 17.3c-.9.6-1.9.9-3 .9s-2.1-.3-3-.9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
        </svg>
      );
    case "west":
      return (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
          <path d="M19 12H7" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M10.4 15.4 7 12l3.4-3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M6.7 15c-.6-.9-.9-1.9-.9-3s.3-2.1.9-3" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
        </svg>
      );
    case "up":
      return (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
          <path d="M12 20V9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M8.6 12.4 12 9l3.4 3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M7.8 7.2h8.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
          <path d="M9.2 6.2 12 4l2.8 2.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
        </svg>
      );
    case "down":
      return (
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
          <path d="M12 4v11" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M15.4 11.6 12 15l-3.4-3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <path d="M7.8 16.8h8.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
          <path d="M9.2 17.8 12 20l2.8-2.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
        </svg>
      );
    default:
      return null;
  }
}

export function EquipmentIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M8 8.5a4 4 0 0 1 8 0" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M7 9h10a3 3 0 0 1 3 3v5.2a3.8 3.8 0 0 1-3.8 3.8H7.8A3.8 3.8 0 0 1 4 17.2V12a3 3 0 0 1 3-3Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M10 13.2h4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12 13.2v1.9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.4 6.9c.8-.9 1.7-1.4 2.6-1.4s1.8.5 2.6 1.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
    </svg>
  );
}

export function WearingIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M9 5.5c1 .9 2 1.4 3 1.4s2-.5 3-1.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.2 6.4 6 7.9v4.6l1.2-.8V20h9.6v-8.3l1.2.8V7.9l-2.2-1.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.2 12.2h5.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
      <path d="M9.2 12.2v2.2c0 .9.7 1.6 1.6 1.6h2.4c.9 0 1.6-.7 1.6-1.6v-2.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
    </svg>
  );
}

export function CharacterAvatarIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 11.2a3.4 3.4 0 1 0 0-6.8 3.4 3.4 0 0 0 0 6.8Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6.6 19.2c.6-3 2.6-4.8 5.4-4.8s4.8 1.8 5.4 4.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M4.8 12a7.2 7.2 0 0 1 14.4 0" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.75" />
    </svg>
  );
}

export function CompassCoreIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 7.3v9.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M7.3 12h9.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12 9.4 14.6 12 12 14.6 9.4 12 12 9.4Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.95" />
      <path d="M5.8 12a6.2 6.2 0 0 1 12.4 0" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.75" />
      <path d="M18.2 12a6.2 6.2 0 0 1-12.4 0" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.75" />
    </svg>
  );
}

export function AttackIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M14.5 4.8 19.2 9.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M18.8 5.2 9.6 14.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.2 15.8 6 18l2.2 2.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.6 14.4 7.3 16.7" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12.7 11.3h3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.95" />
      <path d="M6.6 7.8 10.2 6.4 13.8 7.8v4.2c0 3.7-2.2 6.1-3.6 6.9-1.4-.8-3.6-3.2-3.6-6.9V7.8Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M10.2 7.4v10.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.75" />
      <path d="M10.2 10.1h0" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" opacity="0.85" />
    </svg>
  );
}

export function PickupIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 20V9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.6 12.4 12 9l3.4 3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M5.4 6.8h13.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.82" />
      <path d="M6.8 18.8h10.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.82" />
    </svg>
  );
}

export function WearItemIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M9 5.5c1 .9 2 1.4 3 1.4s2-.5 3-1.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.2 6.4 6 7.9v4.6l1.2-.8V20h9.6v-8.3l1.2.8V7.9l-2.2-1.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.2 12.2h5.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
    </svg>
  );
}

export function DropItemIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 4v11" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M15.4 11.6 12 15l-3.4-3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6.4 18.8h11.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.82" />
      <path d="M8.2 6.2h7.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.7" />
    </svg>
  );
}

export function GiveItemIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 4v11" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.6 11.6 12 15l3.4-3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M19.2 17.2a2.4 2.4 0 1 0 0 .1" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.82" />
      <path d="M4.8 17.2a2.4 2.4 0 1 0 0 .1" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.82" />
    </svg>
  );
}

export function RemoveItemIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M7.6 7.6h8.8l-1 11.1H8.6L7.6 7.6Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.1 7.6V6.4a2 2 0 0 1 2-2h1.8a2 2 0 0 1 2 2v1.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6.3 7.6h11.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M10.4 10.2v5.7M13.6 10.2v5.7" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.86" />
    </svg>
  );
}

export function FleeIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M9.2 6.8a2.1 2.1 0 1 0 0 .1" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.3 9.2 7.9 12.1" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.6 9.7 12 11.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M7.9 12.1 6.2 15.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6.2 15.4 4.9 18.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M10.9 12.3 9.1 14.9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.1 14.9 9.8 18.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.8 10.4 6.3 10.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.95" />
      <path d="M13.8 8.8c1.2 1 2 2.5 2 4.2s-.8 3.2-2 4.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.75" />
      <path d="M16.9 9.9c.7.7 1.1 1.7 1.1 2.9s-.4 2.2-1.1 2.9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.65" />
      <path d="M18.7 12h2.3" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M19.9 10.8 21 12l-1.1 1.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function TellIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M5.8 7.2h12.4A2.8 2.8 0 0 1 21 10v5a2.8 2.8 0 0 1-2.8 2.8H10l-4.2 2.9v-2.9H5.8A2.8 2.8 0 0 1 3 15v-5a2.8 2.8 0 0 1 2.8-2.8Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8 12.3h7.9" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.9" />
      <path d="M8 9.8h4.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.75" />
    </svg>
  );
}

function ArcaneCastIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="4.1" stroke="currentColor" strokeWidth="1.9" />
      <path d="M12 4.7v2.2M12 17.1v2.2M4.7 12h2.2M17.1 12h2.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
      <path d="m7.4 7.4 1.6 1.6m6 6 1.6 1.6m0-9.2L15 9m-6 6-1.6 1.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.82" />
    </svg>
  );
}

function DivineCastIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 4.6v14.8M7.2 9.4h9.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
      <path d="M12 4.6 9.9 7.2M12 4.6l2.1 2.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.86" />
      <path d="M8.4 18.5c.9.7 2.2 1 3.6 1s2.7-.3 3.6-1" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.78" />
    </svg>
  );
}

function WarriorCastIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 5.2 18.2 8v4.7c0 3.6-2.2 5.8-6.2 7.1-4-1.3-6.2-3.5-6.2-7.1V8L12 5.2Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.4 12h5.2M12 9.4v5.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
    </svg>
  );
}

function RogueCastIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M5.5 16.7 16.7 5.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
      <path d="m14.6 5.3 4.1 4.1M4.9 19.1l3.2-.7-2.5-2.5-.7 3.2Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M10.2 13.8h4.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.78" />
    </svg>
  );
}

function EnemyCastIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M4.8 12h10.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
      <path d="m12.1 8.6 3.4 3.4-3.4 3.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M15.7 12h3.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.76" />
    </svg>
  );
}

function AllyCastIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="6.6" stroke="currentColor" strokeWidth="1.9" />
      <path d="M12 8.6v6.8M8.6 12h6.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
    </svg>
  );
}

function SelfCastIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M8.6 6.2a4.8 4.8 0 1 1 6.8 6.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
      <path d="M15.4 17.8a4.8 4.8 0 1 1-6.8-6.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.86" />
      <path d="M15.2 8.2h3.2v-3.2M8.8 15.8H5.6V19" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function SkillCastIcon({
  className,
  classRestriction,
  targetType,
}: {
  className?: string;
  classRestriction?: string | null;
  targetType?: string;
}) {
  const cls = classRestriction?.toUpperCase();
  if (cls === "MAGE") return <ArcaneCastIcon className={className} />;
  if (cls === "CLERIC") return <DivineCastIcon className={className} />;
  if (cls === "WARRIOR") return <WarriorCastIcon className={className} />;
  if (cls === "ROGUE") return <RogueCastIcon className={className} />;

  const target = targetType?.toUpperCase();
  if (target === "ALLY") return <AllyCastIcon className={className} />;
  if (target === "SELF") return <SelfCastIcon className={className} />;
  return <EnemyCastIcon className={className} />;
}

export function TalkIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M6.4 6.2h11.2A2.4 2.4 0 0 1 20 8.6v5.4a2.4 2.4 0 0 1-2.4 2.4H11l-3.6 2.8v-2.8H6.4A2.4 2.4 0 0 1 4 14V8.6a2.4 2.4 0 0 1 2.4-2.4Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.4 10.2h3.6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.82" />
      <path d="M8.4 12.8h6" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.82" />
    </svg>
  );
}

export function MapScrollIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M6 6.8a2.2 2.2 0 1 1 0 4.4m0-4.4h10.8a2.2 2.2 0 0 1 2.2 2.2v8.2a2.8 2.8 0 0 1-2.8 2.8H8.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.2 20a2.4 2.4 0 1 1 0-4.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.2 15.2V6.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.92" />
      <path d="M11.3 10.2c1 .5 1.6 1.3 1.6 2.3 0 1.1-.7 2-1.8 2.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.8" />
      <path d="M13.7 9.4h2.5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.75" />
    </svg>
  );
}

export function RefreshIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M17.6 10.4A6.2 6.2 0 0 0 6.2 12" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6.4 13.6A6.2 6.2 0 0 0 17.8 12" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M14.4 10.4h3.2V7.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.6 13.6H6.4v3.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function CrosshairIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="7.2" stroke="currentColor" strokeWidth="1.9" />
      <circle cx="12" cy="12" r="2.6" stroke="currentColor" strokeWidth="1.9" opacity="0.9" />
      <path d="M12 4v2.8M12 17.2V20M4 12h2.8M17.2 12H20" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
    </svg>
  );
}

// ── Character detail tab icons ──────────────────────────────────────

export function VitalsTabIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 20.4s-7.2-4.8-7.2-10.2a4.6 4.6 0 0 1 7.2-3.8 4.6 4.6 0 0 1 7.2 3.8c0 5.4-7.2 10.2-7.2 10.2Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12 9.6v4.8M9.6 12h4.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.82" />
    </svg>
  );
}

export function EffectsTabIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M12 3.6 13.6 9l5.6.2-4.4 3.4 1.6 5.4-4.4-3.2-4.4 3.2 1.6-5.4L4.8 9.2l5.6-.2L12 3.6Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function AchievementsTabIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M8.2 4.8h7.6v6.4c0 2.2-1.7 3.8-3.8 3.8s-3.8-1.6-3.8-3.8V4.8Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.2 7.2H6a1.6 1.6 0 0 0-1.6 1.6v.8A2.4 2.4 0 0 0 6.8 12h1.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.82" />
      <path d="M15.8 7.2H18a1.6 1.6 0 0 1 1.6 1.6v.8A2.4 2.4 0 0 1 17.2 12h-1.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.82" />
      <path d="M10 15v1.4c0 .6-.4 1.2-1 1.4L8 18.2h8l-1-.4c-.6-.2-1-.8-1-1.4V15" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M7.6 19.2h8.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" />
    </svg>
  );
}

export function QuestsTabIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M6 6.8a2.2 2.2 0 1 1 0 4.4m0-4.4h10.8a2.2 2.2 0 0 1 2.2 2.2v8.2a2.8 2.8 0 0 1-2.8 2.8H8.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.2 20a2.4 2.4 0 1 1 0-4.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8.2 15.2V6.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.92" />
      <path d="M11 10.6h4.4M11 13.2h3" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.78" />
    </svg>
  );
}

export function HelpIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="8.4" stroke="currentColor" strokeWidth="1.9" />
      <path d="M9.6 9.4a2.6 2.6 0 0 1 5 .8c0 1.4-1.8 1.8-2.4 2.4-.2.2-.2.5-.2.8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="12" cy="16.2" r="0.1" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}

export function ExpandRoomIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M8 4.8H4.8V8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M16 4.8h3.2V8" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8 19.2H4.8V16" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M16 19.2h3.2V16" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.4 9.4h5.2v5.2H9.4z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.82" />
    </svg>
  );
}

export function ChatBubbleIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M5.4 5.8h13.2A2.4 2.4 0 0 1 21 8.2v6a2.4 2.4 0 0 1-2.4 2.4H10.8l-4 3v-3H5.4A2.4 2.4 0 0 1 3 14.2v-6a2.4 2.4 0 0 1 2.4-2.4Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M8 10h8M8 12.8h5" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.8" />
    </svg>
  );
}

export function GlobeIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <circle cx="12" cy="12" r="8.2" stroke="currentColor" strokeWidth="1.9" />
      <path d="M3.8 12h16.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.78" />
      <path d="M12 3.8c2.2 2.4 3.4 5.2 3.4 8.2s-1.2 5.8-3.4 8.2c-2.2-2.4-3.4-5.2-3.4-8.2s1.2-5.8 3.4-8.2Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function TerminalIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <rect x="3.6" y="4.8" width="16.8" height="14.4" rx="2.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M7.2 9.6 10 12l-2.8 2.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M12.4 14.4h4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" opacity="0.8" />
    </svg>
  );
}

export function ShopIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M4 7h16l-1.6 9.2a2 2 0 0 1-2 1.8H7.6a2 2 0 0 1-2-1.8L4 7Z" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M4 7 5.6 4h12.8L20 7" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M9.6 10.4v1.2a2.4 2.4 0 0 0 4.8 0v-1.2" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" opacity="0.8" />
    </svg>
  );
}

export function SpellbookIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M4 4.5A2.5 2.5 0 0 1 6.5 2H20v16H6.5A2.5 2.5 0 0 0 4 20.5V4.5Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M4 18h16v2.5a1.5 1.5 0 0 1-1.5 1.5H6.5A2.5 2.5 0 0 1 4 19.5V18Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
      <path d="M12 6l1.8 2.5L12 11l-1.8-2.5L12 6Z" stroke="currentColor" strokeWidth="1.4" strokeLinejoin="round" opacity="0.7" />
      <circle cx="12" cy="8.5" r="0.8" fill="currentColor" opacity="0.6" />
    </svg>
  );
}

export function SendIcon({ className }: { className?: string }) {
  return (
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" className={className} aria-hidden="true">
      <path d="M4.4 12h15.2M13.2 5.6 19.6 12l-6.4 6.4" stroke="currentColor" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
