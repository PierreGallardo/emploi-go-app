import type { ReactNode } from 'react';
import { useLocation } from 'react-router';
import { Brand } from './brand';

export function AuthLayout({ children }: { children: ReactNode }) {
  const { pathname } = useLocation();
  return (
    <div className="flex min-h-svh">
      <main
        id="main-content"
        className="flex min-w-0 flex-1 flex-col items-center px-4 pt-10 pb-12 sm:px-8 lg:justify-center lg:px-12 lg:py-10"
      >
        <div className="flex w-full max-w-[380px] flex-col items-center gap-4">
          <Brand withName={false} className="[&>svg]:h-16" />
          <div
            key={pathname}
            className="w-full animate-in duration-200 ease-out fade-in-0 slide-in-from-bottom-1 motion-reduce:animate-none"
          >
            {children}
          </div>
        </div>
      </main>
      <aside className="hidden p-3 lg:block lg:w-[46%] xl:w-1/2">
        <div className="sticky top-3 h-[calc(100svh-1.5rem)] overflow-hidden rounded-[calc(var(--radius)+0.5rem)] bg-primary text-primary-foreground">
          <MapPreview />
          <div className="absolute inset-x-0 bottom-0 bg-linear-to-t from-primary from-50% to-transparent px-12 pt-40 pb-14">
            <p className="text-4xl leading-tight font-semibold tracking-tight xl:text-5xl">
              Trouve sur une carte les entreprises de ton métier, près de chez
              toi.
            </p>
          </div>
        </div>
      </aside>
    </div>
  );
}

const width = 640;
const height = 860;
const avenues = [150, 400];
const streets = [270, 530];
const boulevards = [160, 690];
const lanes = [330, 580];
const river = { top: 430, bottom: 500 };

/** The blocks fill the space between the roads, kept off the river. */
const blocks = (() => {
  const xs = [0, ...[...avenues, ...streets].sort((a, b) => a - b), width];
  const ys = [0, ...[...boulevards, ...lanes].sort((a, b) => a - b), height];
  const cells = [];
  for (let i = 0; i < xs.length - 1; i++)
    for (let j = 0; j < ys.length - 1; j++) {
      const top = ys[j]!;
      const bottom = ys[j + 1]!;
      const cut =
        top < river.bottom && bottom > river.top
          ? [
              [top, river.top],
              [river.bottom, bottom],
            ]
          : [[top, bottom]];
      for (const [from, to] of cut)
        if (to! - from! > 40)
          cells.push({
            x: xs[i]! + 14,
            y: from! + 14,
            width: xs[i + 1]! - xs[i]! - 28,
            height: to! - from! - 28,
          });
    }
  return cells;
})();

const park = blocks.find((block) => block.x > 400 && block.y > 580)!;

const pins = [
  [80, 90],
  [330, 230],
  [480, 70],
  [585, 250],
  [210, 390],
  [90, 640],
  [330, 620],
  [470, 790],
  [210, 780],
] as const;

function MapPreview() {
  return (
    <svg
      aria-hidden="true"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="xMidYMid slice"
      className="absolute inset-0 size-full"
    >
      <g fill="currentColor" fillOpacity="0.07">
        {blocks.map((block) => (
          <rect key={`${block.x}-${block.y}`} rx="12" {...block} />
        ))}
      </g>
      <rect rx="12" {...park} fill="currentColor" fillOpacity="0.1" />
      <g fill="currentColor" fillOpacity="0.14">
        {[0.25, 0.55, 0.8].map((left, index) => (
          <circle
            key={left}
            cx={park.x + park.width * left}
            cy={park.y + park.height * (index % 2 ? 0.65 : 0.35)}
            r="14"
          />
        ))}
      </g>
      <path
        d={`M0 ${river.top + 30} C 140 ${river.top - 10}, 260 ${river.bottom + 10}, 400 ${river.top + 34} S 560 ${river.top + 10}, ${width} ${river.top + 40}`}
        fill="none"
        stroke="currentColor"
        strokeWidth="34"
        strokeOpacity="0.16"
        strokeLinecap="round"
      />
      <g stroke="currentColor" strokeLinecap="round">
        <g strokeWidth="12" strokeOpacity="0.26">
          {avenues.map((x) => (
            <line key={x} x1={x} y1="0" x2={x} y2={height} />
          ))}
          {boulevards.map((y) => (
            <line key={y} x1="0" y1={y} x2={width} y2={y} />
          ))}
        </g>
        <g strokeWidth="5" strokeOpacity="0.18">
          {streets.map((x) => (
            <line key={x} x1={x} y1="0" x2={x} y2={height} />
          ))}
          {lanes.map((y) => (
            <line key={y} x1="0" y1={y} x2={width} y2={y} />
          ))}
        </g>
      </g>
      {pins.map(([x, y]) => (
        <g key={`${x}-${y}`} transform={`translate(${x} ${y})`}>
          <circle r="16" fill="currentColor" fillOpacity="0.2" />
          <circle r="8" fill="currentColor" />
          <circle r="3" className="fill-primary" />
        </g>
      ))}
      <g transform="translate(90 250)">
        <circle r="24" fill="currentColor" />
        <text
          textAnchor="middle"
          dominantBaseline="central"
          className="fill-primary text-[15px] font-bold"
        >
          12
        </text>
      </g>
      <g transform="translate(400 330)">
        <circle r="40" fill="currentColor" fillOpacity="0.14" />
        <circle r="16" fill="currentColor" />
        <circle r="6" className="fill-primary" />
      </g>
      <g transform="translate(300 190)">
        <path d="M100 120 L 100 136 L 84 120 Z" fill="currentColor" />
        <rect
          width="260"
          height="120"
          rx="22"
          fill="currentColor"
          className="drop-shadow-xl"
        />
        <rect
          x="20"
          y="22"
          width="48"
          height="48"
          rx="14"
          className="fill-primary"
          fillOpacity="0.14"
        />
        <rect
          x="84"
          y="28"
          width="140"
          height="13"
          rx="6.5"
          className="fill-primary"
          fillOpacity="0.6"
        />
        <rect
          x="84"
          y="52"
          width="96"
          height="10"
          rx="5"
          className="fill-primary"
          fillOpacity="0.25"
        />
        <rect
          x="20"
          y="88"
          width="72"
          height="18"
          rx="9"
          className="fill-primary"
          fillOpacity="0.14"
        />
        <rect
          x="102"
          y="88"
          width="56"
          height="18"
          rx="9"
          className="fill-primary"
          fillOpacity="0.14"
        />
      </g>
    </svg>
  );
}
