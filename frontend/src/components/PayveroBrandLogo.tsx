import React from 'react'

interface PayveroBrandLogoProps {
  className?: string
  iconOnly?: boolean
  size?: 'sm' | 'md' | 'lg'
}

export const PayveroBrandLogo: React.FC<PayveroBrandLogoProps> = ({
  className = '',
  iconOnly = false,
  size = 'md',
}) => {
  const sizeMap = {
    sm: { icon: 24, font: 'text-lg' },
    md: { icon: 32, font: 'text-xl' },
    lg: { icon: 44, font: 'text-3xl' },
  }

  const { icon: iconSize, font: fontClass } = sizeMap[size]

  return (
    <div className={`flex items-center gap-3 select-none ${className}`}>
      <div className="relative flex items-center justify-center rounded-xl bg-gradient-to-br from-indigo-500 via-emerald-500 to-teal-400 p-0.5 shadow-lg shadow-indigo-500/20">
        <div className="flex items-center justify-center rounded-[10px] bg-slate-950 p-1.5 backdrop-blur-md">
          <svg
            width={iconSize}
            height={iconSize}
            viewBox="0 0 32 32"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
            className="text-emerald-400"
          >
            {/* Hexagonal Shield Structure */}
            <path
              d="M16 3L27 8.5V16C27 22.5 22 27.5 16 29C10 27.5 5 22.5 5 16V8.5L16 3Z"
              stroke="currentColor"
              strokeWidth="2.5"
              strokeLinecap="round"
              strokeLinejoin="round"
              className="text-emerald-400"
            />
            {/* Inner Payment Vector Stream */}
            <path
              d="M11 15.5L14.5 19L21.5 11.5"
              stroke="url(#payvero-grad)"
              strokeWidth="2.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            <defs>
              <linearGradient
                id="payvero-grad"
                x1="11"
                y1="11.5"
                x2="21.5"
                y2="19"
                gradientUnits="userSpaceOnUse"
              >
                <stop stopColor="#34d399" />
                <stop offset="1" stopColor="#818cf8" />
              </linearGradient>
            </defs>
          </svg>
        </div>
      </div>
      {!iconOnly && (
        <div className="flex flex-col">
          <span className={`font-black tracking-tight text-slate-100 ${fontClass}`}>
            PAY<span className="text-emerald-400">VERO</span>
          </span>
          <span className="text-[10px] font-semibold tracking-wider text-slate-400 uppercase -mt-1">
            Accounting Engine
          </span>
        </div>
      )}
    </div>
  )
}
