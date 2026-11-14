import { Fragment, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Box,
  Group,
  Paper,
  SegmentedControl,
  SimpleGrid,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { pl } from '@/shared/i18n/pl';
import { ChartCard } from './ChartCard';
import { passKindColors } from './chartColors';
import type { PassSalesReport } from './passSalesApi';
import { periodLength } from './reportPeriod';
import { ReportFrame } from './ReportFrame';
import {
  AverageValueTile,
  PassesSoldTile,
  RevenueTile,
  SalesRanking,
  SalesTrendChart,
  TopProductTile,
} from './SalesWidgets';
import { describeVariant, formatAmount, type SalesGrouping, type SalesMeasure } from './salesMath';
import { bucketFor, formatNumber, formatPercent, ratio } from './statisticsMath';
import { usePassSales } from './usePassSales';
import type { StatisticsFilters } from './useStatisticsFilters';

const texts = pl.statistics.sales;

/**
 * Which passes clients buy most, by kind of pass, product and price variant (#167). It covers the
 * whole studio: the event filters do not apply to it.
 */
export function SalesPanel({ filters }: { filters: StatisticsFilters }) {
  const { report, stale } = usePassSales(filters);
  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        {texts.rules}
      </Text>
      {filters.eventsFiltered && (
        <Alert color="gray">{pl.statistics.filters.salesNotFiltered}</Alert>
      )}
      <ReportFrame report={report} stale={stale} loadError={texts.loadError}>
        {(data) => (data.totals.passes === 0 ? <NoSales /> : <SalesReport report={data} />)}
      </ReportFrame>
    </Stack>
  );
}

function NoSales() {
  return (
    <Paper withBorder radius="md" py={48} px="md">
      <Stack gap={6} align="center">
        <Text fw={500}>{texts.empty}</Text>
        <Text size="sm" c="dimmed" ta="center">
          {texts.emptyHint}
        </Text>
      </Stack>
    </Paper>
  );
}

function SalesReport({ report }: { report: PassSalesReport }) {
  const [grouping, setGrouping] = useState<SalesGrouping>('kind');
  const [measure, setMeasure] = useState<SalesMeasure>('passes');
  const [trendMeasure, setTrendMeasure] = useState<SalesMeasure>('passes');
  return (
    <>
      <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }}>
        <RevenueTile report={report} />
        <PassesSoldTile report={report} />
        <AverageValueTile report={report} />
        <TopProductTile report={report} />
      </SimpleGrid>
      <SimpleGrid cols={{ base: 1, md: 2 }}>
        <ChartCard title={texts.rankingTitle[grouping]} subtitle={texts.rankingSubtitle[measure]}>
          <Group gap="sm" wrap="wrap">
            <SegmentedControl
              size="xs"
              aria-label={texts.groupByLabel}
              value={grouping}
              onChange={(value) => setGrouping(value as SalesGrouping)}
              data={(['kind', 'product'] as const).map((value) => ({
                value,
                label: texts.groupBy[value],
              }))}
            />
            <MeasureControl value={measure} onChange={setMeasure} />
          </Group>
          <SalesRanking report={report} grouping={grouping} measure={measure} />
        </ChartCard>
        <ChartCard
          title={texts.trendTitle}
          subtitle={texts.trendSubtitle[bucketFor(periodLength(report))]}
          actions={<MeasureControl value={trendMeasure} onChange={setTrendMeasure} />}
        >
          <SalesTrendChart report={report} measure={trendMeasure} />
        </ChartCard>
      </SimpleGrid>
      <ChartCard title={texts.tableTitle} subtitle={texts.tableSubtitle}>
        <ProductsTable report={report} />
      </ChartCard>
    </>
  );
}

function MeasureControl({
  value,
  onChange,
}: {
  value: SalesMeasure;
  onChange: (value: SalesMeasure) => void;
}) {
  return (
    <SegmentedControl
      size="xs"
      aria-label={texts.measureLabel}
      value={value}
      onChange={(next) => onChange(next as SalesMeasure)}
      data={(['passes', 'revenue'] as const).map((measure) => ({
        value: measure,
        label: texts.measure[measure],
      }))}
    />
  );
}

const numeric = { fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' } as const;

/** Every product sold with its counts; a product opens to its price variants. */
function ProductsTable({ report }: { report: PassSalesReport }) {
  const [open, setOpen] = useState<ReadonlySet<string>>(new Set());
  const columns = texts.columns;
  const money = (amountMinor: number) => formatAmount(amountMinor, report.currency);
  const share = (amountMinor: number) =>
    formatPercent(ratio(amountMinor, report.totals.revenueMinor));

  function toggle(productId: string) {
    setOpen((current) => {
      const next = new Set(current);
      if (!next.delete(productId)) {
        next.add(productId);
      }
      return next;
    });
  }

  return (
    <Table.ScrollContainer minWidth={760}>
      <Table verticalSpacing="xs" aria-label={texts.tableTitle}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{columns.product}</Table.Th>
            <Table.Th>{columns.kind}</Table.Th>
            <Table.Th ta="right">{columns.passes}</Table.Th>
            <Table.Th ta="right">{columns.online}</Table.Th>
            <Table.Th ta="right">{columns.studio}</Table.Th>
            <Table.Th ta="right">{columns.revenue}</Table.Th>
            <Table.Th ta="right">{columns.share}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {report.products.map((product) => {
            const expanded = open.has(product.productId);
            return (
              <Fragment key={product.productId}>
                <Table.Tr>
                  <Table.Td>
                    <Group gap={6} wrap="nowrap">
                      <ActionIcon
                        variant="subtle"
                        color="gray"
                        size="sm"
                        aria-expanded={expanded}
                        aria-label={
                          expanded ? texts.collapse(product.name) : texts.expand(product.name)
                        }
                        onClick={() => toggle(product.productId)}
                      >
                        {expanded ? '▾' : '▸'}
                      </ActionIcon>
                      <Text size="sm">{product.name}</Text>
                    </Group>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={8} wrap="nowrap">
                      <Box
                        w={10}
                        h={10}
                        style={{
                          flexShrink: 0,
                          borderRadius: 2,
                          background: passKindColors[product.kind],
                        }}
                      />
                      <Text size="sm">{texts.kinds[product.kind]}</Text>
                    </Group>
                  </Table.Td>
                  <Table.Td ta="right" style={numeric}>
                    {formatNumber(product.counts.passes)}
                    {product.counts.free > 0 && (
                      <Text span size="sm" c="dimmed">
                        {' '}
                        {texts.freeInRow(product.counts.free)}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td ta="right" style={numeric}>
                    {formatNumber(product.counts.online)}
                  </Table.Td>
                  <Table.Td ta="right" style={numeric}>
                    {formatNumber(product.counts.studio)}
                  </Table.Td>
                  <Table.Td ta="right" style={numeric}>
                    {money(product.counts.revenueMinor)}
                  </Table.Td>
                  <Table.Td ta="right" style={numeric}>
                    {share(product.counts.revenueMinor)}
                  </Table.Td>
                </Table.Tr>
                {expanded &&
                  product.variants.map((variant) => (
                    <Table.Tr key={variant.variantId} bg="var(--mantine-color-gray-0)">
                      <Table.Td pl={44}>
                        <Text size="sm" c="dimmed">
                          {describeVariant(product, variant, report.currency)}
                        </Text>
                      </Table.Td>
                      <Table.Td />
                      <Table.Td ta="right" style={numeric} c="dimmed">
                        {formatNumber(variant.counts.passes)}
                      </Table.Td>
                      <Table.Td ta="right" style={numeric} c="dimmed">
                        {formatNumber(variant.counts.online)}
                      </Table.Td>
                      <Table.Td ta="right" style={numeric} c="dimmed">
                        {formatNumber(variant.counts.studio)}
                      </Table.Td>
                      <Table.Td ta="right" style={numeric} c="dimmed">
                        {money(variant.counts.revenueMinor)}
                      </Table.Td>
                      <Table.Td ta="right" style={numeric} c="dimmed">
                        {share(variant.counts.revenueMinor)}
                      </Table.Td>
                    </Table.Tr>
                  ))}
              </Fragment>
            );
          })}
        </Table.Tbody>
        <Table.Tfoot>
          <Table.Tr fw={600}>
            <Table.Td>{texts.total}</Table.Td>
            <Table.Td />
            <Table.Td ta="right" style={numeric}>
              {formatNumber(report.totals.passes)}
            </Table.Td>
            <Table.Td ta="right" style={numeric}>
              {formatNumber(report.totals.online)}
            </Table.Td>
            <Table.Td ta="right" style={numeric}>
              {formatNumber(report.totals.studio)}
            </Table.Td>
            <Table.Td ta="right" style={numeric}>
              {money(report.totals.revenueMinor)}
            </Table.Td>
            <Table.Td ta="right" style={numeric}>
              {report.totals.revenueMinor > 0 ? '100%' : '—'}
            </Table.Td>
          </Table.Tr>
        </Table.Tfoot>
      </Table>
    </Table.ScrollContainer>
  );
}
