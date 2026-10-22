/** 기보: 둔 수의 표기를 순서대로 보여준다. 둔 수가 없으면 그리지 않는다. */
export function OmokMoveList({ history }: { history: string[] }) {
  if (history.length === 0) return null;
  return (
    <div className="max-h-36 w-full overflow-y-auto rounded-lg border bg-card p-2">
      <ol className="flex flex-wrap gap-x-3 text-sm tabular-nums">
        {history.map((n, i) => (
          <li key={i}>
            <span className="mr-1 text-muted-foreground">{i + 1}.</span>
            {n}
          </li>
        ))}
      </ol>
    </div>
  );
}
