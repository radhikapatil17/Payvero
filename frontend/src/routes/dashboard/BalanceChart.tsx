import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { buildBalanceSeries } from './buildBalanceSeries'
import { formatCompactMoney, formatMoney } from '@/lib/money'
import type { Transfer } from '@/lib/api/types'

const DAY_LABEL = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' })

export function BalanceChart({
  transfers,
  currentBalanceMinorUnits,
  currency = 'USD',
}: {
  transfers: readonly Transfer[]
  currentBalanceMinorUnits: number
  currency?: string
}) {
  const { points, domain } = buildBalanceSeries(transfers, currentBalanceMinorUnits)

  return (
    <div className="h-56 w-full" data-testid="balance-chart" data-points={points.length}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 8 }}>
          <CartesianGrid strokeDasharray="3 3" vertical={false} className="stroke-border" />
          <XAxis
            dataKey="timestamp"
            /**
             * A real time scale, not a category axis. With categories a fortnight
             * of silence and a minute of silence occupy the same width, which
             * makes the chart say something untrue about when money moved.
             */
            type="number"
            scale="time"
            domain={domain}
            tickFormatter={(value: number) => DAY_LABEL.format(new Date(value))}
            tick={{ fontSize: 11 }}
            stroke="currentColor"
            className="text-muted-foreground"
            minTickGap={24}
          />
          <YAxis
            tickFormatter={(value: number) => formatCompactMoney(value, currency)}
            tick={{ fontSize: 11 }}
            stroke="currentColor"
            className="text-muted-foreground"
            width={56}
          />
          <Tooltip
            labelFormatter={(value) => DAY_LABEL.format(new Date(Number(value)))}
            formatter={(value) => [formatMoney(Number(value), currency), 'Balance']}
            contentStyle={{ fontSize: 12 }}
          />
          <Line
            type="stepAfter"
            dataKey="balanceMinorUnits"
            stroke="var(--color-primary)"
            strokeWidth={2}
            /**
             * Dots stay on: with one or two movements the line has almost no
             * length to see, and the dot is the only thing that renders.
             */
            dot={{ r: 3 }}
            activeDot={{ r: 5 }}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
