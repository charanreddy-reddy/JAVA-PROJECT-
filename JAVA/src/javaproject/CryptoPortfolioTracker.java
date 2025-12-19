package javaproject;

import java.io.*;
import java.util.*;

class Trade {
    String symbol;
    double quantity;
    double price;

    public Trade(String symbol, double quantity, double price) {
        this.symbol = symbol;
        this.quantity = quantity;
        this.price = price;
    }
}

abstract class Asset {
    String symbol;
    double quantity;

    public Asset(String symbol, double quantity) {
        this.symbol = symbol;
        this.quantity = quantity;
    }

    public abstract double getValuation(double marketPrice);
}

class Crypto extends Asset {
    double avgBuyPrice;

    public Crypto(String symbol, double quantity, double avgBuyPrice) {
        super(symbol, quantity);
        this.avgBuyPrice = avgBuyPrice;
    }

  
    public double getValuation(double marketPrice) {
        return quantity * marketPrice;
    }

    
    public double getPnL(double marketPrice) {
        return quantity * (marketPrice - avgBuyPrice);
    }

    public double getProfitPercent(double marketPrice) {
        return ((marketPrice - avgBuyPrice) / avgBuyPrice) * 100;
    }
}

interface ValuationService {
    double getMarketPrice(String symbol) throws SymbolNotFoundException;
}

interface PriceAlertObserver {
    void onPriceChange(String symbol, double newPrice);
}

class SymbolNotFoundException extends Exception {
    private static final long serialVersionUID = 1L;

    public SymbolNotFoundException(String message) {
        super(message);
    }
}

class InvalidTradeException extends Exception {
    private static final long serialVersionUID = 1L;

    public InvalidTradeException(String message) {
        super(message);
    }
}

class PortfolioManager {
    private final Map<String, List<Trade>> tradeMap = new HashMap<>();
    private final ValuationService valuationService;
    private final List<PriceAlertObserver> observers = new ArrayList<>();

    public PortfolioManager(ValuationService valuationService) {
        this.valuationService = valuationService;
    }

    public void addObserver(PriceAlertObserver observer) {
        observers.add(observer);
    }

    private void notifyObservers(String symbol, double newPrice) {
        for (PriceAlertObserver observer : observers) {
            observer.onPriceChange(symbol, newPrice);
        }
    }

    public void addTrade(Trade trade) throws InvalidTradeException {
        if (trade.quantity <= 0 || trade.price <= 0) {
            throw new InvalidTradeException("Invalid trade values.");
        }
        tradeMap.computeIfAbsent(trade.symbol, k -> new ArrayList<>()).add(trade);
    }

   
    public List<Crypto> computeHoldings(Map<String, Double> priceCache) throws SymbolNotFoundException {
        List<Crypto> holdings = new ArrayList<>();
        priceCache.clear();

        for (String symbol : tradeMap.keySet()) {
            List<Trade> trades = tradeMap.get(symbol);
            double totalQty = 0, totalCost = 0;

            for (Trade t : trades) {
                totalQty += t.quantity;
                totalCost += t.quantity * t.price;
            }
            if (totalQty == 0) continue;

            double avgPrice = totalCost / totalQty;
            double marketPrice = valuationService.getMarketPrice(symbol);

            holdings.add(new Crypto(symbol, totalQty, avgPrice));
            priceCache.put(symbol, marketPrice);

            notifyObservers(symbol, marketPrice);
        }

        holdings.sort((a, b) -> Double.compare(
                b.getValuation(priceCache.get(b.symbol)),
                a.getValuation(priceCache.get(a.symbol))
        ));
        return holdings;
    }

    public void exportReport(String filename, Map<String, Double> priceCache, double usdToInr) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filename))) {
            List<Crypto> holdings = computeHoldings(priceCache);

            for (Crypto c : holdings) {
                double marketPrice = priceCache.get(c.symbol);
                double valueUsd = c.getValuation(marketPrice);
                double pnlUsd = c.getPnL(marketPrice);
                double valueInr = valueUsd * usdToInr;
                double pnlInr = pnlUsd * usdToInr;

                writer.printf(
                    "%s, Qty: %.4f, Value: $%.2f (₹%.2f), P&L: $%.2f (₹%.2f), Profit%%: %.2f%%%n",
                    c.symbol,
                    c.quantity,
                    valueUsd, valueInr,
                    pnlUsd, pnlInr,
                    c.getProfitPercent(marketPrice)
                );
            }
        } catch (IOException e) {
            System.err.println("I/O error while exporting report: " + e.getMessage());
        } catch (SymbolNotFoundException e) {
            System.err.println("Symbol lookup failed: " + e.getMessage());
        }
    }
}

public class CryptoPortfolioTracker {

   
    static final double USD_TO_INR = 84.0;

    public static void main(String[] args) {
        System.out.println("=== Crypto Portfolio Tracker Started ===");

        Map<String, Double> prices = loadPrices("prices.csv");
        ValuationService valuationService = symbol -> {
            Double price = prices.get(symbol);
            if (price == null) throw new SymbolNotFoundException("Symbol not found: " + symbol);
            return price;
        };

        PortfolioManager manager = new PortfolioManager(valuationService);

        manager.addObserver((symbol, price) -> {
            System.out.printf("Price update: %s → $%.2f (₹%.2f)%n",
                    symbol, price, price * USD_TO_INR);
            if (price > 1000) {
                try (PrintWriter alert = new PrintWriter(new FileWriter("alerts.txt", true))) {
                    alert.printf("ALERT [%s]: %s exceeded $1000 → $%.2f (₹%.2f)%n",
                            new java.util.Date().toString(), symbol, price, price * USD_TO_INR);
                } catch (IOException e) {
                    System.err.println(" Failed to write alert.");
                }
            }
        });

        try (BufferedReader reader = new BufferedReader(new FileReader("trades.csv"))) {
            System.out.println(" Loading trades from trades.csv...");

            Set<String> seenTrades = new HashSet<>();   // skip exact duplicate lines
            int tradeCount = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (!seenTrades.add(line)) {
                    // duplicate line, skip
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length == 3) {
                    try {
                        String symbol = parts[0].trim();
                        double quantity = Double.parseDouble(parts[1].trim());
                        double price = Double.parseDouble(parts[2].trim());
                        manager.addTrade(new Trade(symbol, quantity, price));
                        System.out.printf(" %s: %.4f @ $%.2f%n", symbol, quantity, price);
                        tradeCount++;
                    } catch (NumberFormatException | InvalidTradeException e) {
                        System.err.println(" Skipping invalid line: " + line);
                    }
                }
            }

            System.out.printf("Loaded %d trades successfully%n", tradeCount);

            Map<String, Double> priceCache = new HashMap<>();
            manager.exportReport("pnl_report.csv", priceCache, USD_TO_INR);

            // Reuse holdings and price cache for summary
            List<Crypto> holdings = manager.computeHoldings(priceCache);
            double totalValueUsd = 0;

            for (Crypto c : holdings) {
                double price = priceCache.get(c.symbol);
                totalValueUsd += c.getValuation(price);
            }
            double totalValueInr = totalValueUsd * USD_TO_INR;

            System.out.printf(
                " Portfolio Summary: %d holdings, Total Value: $%.2f (₹%.2f)%n",
                holdings.size(), totalValueUsd, totalValueInr
            );
            System.out.println(" Report generated: pnl_report.csv");

        } catch (Exception e) {
            System.err.println(" Error: " + e.getMessage());
            
        }
    }

    private static Map<String, Double> loadPrices(String filename) {
        Map<String, Double> prices = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filename))) {
            System.out.println(" Loading prices from " + filename + "...");
            int priceCount = 0;
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split(",");
                if (parts.length == 2) {
                    try {
                        String symbol = parts[0].trim();
                        double price = Double.parseDouble(parts[1].trim());
                        prices.put(symbol, price);
                        System.out.printf("  %s: $%.2f (₹%.2f)%n",
                                symbol, price, price * USD_TO_INR);
                        priceCount++;
                    } catch (NumberFormatException e) {
                        System.err.println(" Invalid price format: " + line);
                    }
                }
            }
            System.out.printf(" Loaded %d price quotes%n", priceCount);
        } catch (IOException e) {
            System.err.println(" Failed to load prices: " + e.getMessage());
        }
        return prices;
    }
}
