"use client";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { NEWS_CATEGORIES } from "../types";

interface NewsFiltersProps {
  selectedCategory: string | null;
  minImportance: number | null;
  dateFrom: string;
  dateTo: string;
  onSelectCategory: (cat: string | null) => void;
  onSelectImportance: (imp: number | null) => void;
  onSelectDateFrom: (date: string) => void;
  onSelectDateTo: (date: string) => void;
}

export function NewsFilters({
  selectedCategory,
  minImportance,
  dateFrom,
  dateTo,
  onSelectCategory,
  onSelectImportance,
  onSelectDateFrom,
  onSelectDateTo,
}: NewsFiltersProps) {
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium text-muted-foreground">카테고리:</span>
        <Button
          variant={selectedCategory === null ? "default" : "outline"}
          size="sm"
          onClick={() => onSelectCategory(null)}
          className="cursor-pointer transition-colors duration-200"
        >
          전체
        </Button>
        {NEWS_CATEGORIES.map((cat) => (
          <Button
            key={cat}
            variant={selectedCategory === cat ? "default" : "outline"}
            size="sm"
            onClick={() => onSelectCategory(cat)}
            className="cursor-pointer transition-colors duration-200 capitalize"
          >
            {cat}
          </Button>
        ))}

        <span className="ml-4 text-sm font-medium text-muted-foreground">중요도:</span>
        {[1, 3, 4].map((imp) => (
          <Button
            key={imp}
            variant={minImportance === imp ? "default" : "outline"}
            size="sm"
            onClick={() => onSelectImportance(minImportance === imp ? null : imp)}
            className="cursor-pointer transition-colors duration-200"
          >
            {imp}+
          </Button>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm font-medium text-muted-foreground">기간:</span>
        <Input
          type="date"
          value={dateFrom}
          onChange={(e) => onSelectDateFrom(e.target.value)}
          className="w-auto cursor-pointer"
        />
        <span className="text-sm text-muted-foreground">~</span>
        <Input
          type="date"
          value={dateTo}
          onChange={(e) => onSelectDateTo(e.target.value)}
          className="w-auto cursor-pointer"
        />
      </div>
    </div>
  );
}
