package javastuff;

import javastuff.onto.BookInfo;
import javastuff.onto.Goal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class BookTraderLogic {
    public final long USE_AVERAGES_AFTER = 10000;//in ms
    public final long STOP_TRADING_NONGOAL_BOOKS = 15000;//in ms
    public final long TRADING_DURATION = 18000;//in ms
    public final double MARGIN = 0.1;
    public final double SMOOTHING_FACTOR = 0.5;

    long time = 0;
    BookTrader agent;

    double minBookPrice;
    double maxBookPrice;
    /**
     * Purchase proposals we get / exponential moving average
     */
    HashMap<Integer, Double> purchaseEma = new HashMap<>();
    /**
     * Sale proposals / exponential moving average
     */
    HashMap<Integer, Double> saleEma = new HashMap<>();
    HashMap<Integer, Double> goals = new HashMap<>();
    HashSet<Integer> books = new HashSet<>();

    private class PriceTimestamp {
        double price;
        long time;

        public PriceTimestamp(long time, double price) {
            this.price = price;
            this.time = time;
        }
    }

    public class BookPriceTuple {
        public int book;
        public double price;

        public BookPriceTuple(int id, double price) {
            this.book = id;
            this.price = price;
        }
    }

    public BookTraderLogic(BookTrader agent) {
        this.agent = agent;
    }

    public void startTrading() {
        time = System.currentTimeMillis();

        goals.clear();
        books.clear();

        for (Goal g : agent.myGoal)
            goals.put(g.getBook().getBookID(), g.getValue());

        for (BookInfo b : agent.myBooks)
            books.add(b.getBookID());

        minBookPrice = goals.values().stream().min(Double::compareTo).get();
        maxBookPrice = goals.values().stream().max(Double::compareTo).get();
    }

    /**
     * Proposes books and prices for purchase
     *
     * @return
     */
    public ArrayList<BookPriceTuple> proposePurchase() {
        ArrayList<BookPriceTuple> proposal = new ArrayList<>();

        if ((System.currentTimeMillis() - time) < STOP_TRADING_NONGOAL_BOOKS)
            for (Integer b : saleEma.keySet()) {
                double p = saleEma.get(b) * (1 - MARGIN);
                if (purchaseEma.containsKey(b))
                    p = Math.min(p, purchaseEma.get(b));
                proposal.add(new BookPriceTuple(b, p));
            }

        for (Integer b : goals.keySet()) {
            proposal.add(new BookPriceTuple(b, goals.get(b) * (1 - MARGIN)));
        }

        return proposal;
    }

    /**
     * Filters out proposals
     *
     * @param proposal
     * @return
     */
    public List<BookPriceTuple> acceptPurchase(ArrayList<BookPriceTuple> proposal) {
        //register proposal, compute averages
        for (BookPriceTuple p :
                proposal) {
            double price = p.price;
            if (purchaseEma.containsKey(p.book))
                price = SMOOTHING_FACTOR * price + (1 - SMOOTHING_FACTOR) * purchaseEma.get(p.book);
            purchaseEma.put(p.book, price);
        }

        //filter items
        return proposal.stream()
                .filter(p -> p.price <= estimateBookUtility(p.book))
                .collect(Collectors.toList());
    }

    /**
     * Filters out proposals
     *
     * @param proposal
     * @return
     */
    public List<BookPriceTuple> acceptSale(ArrayList<BookPriceTuple> proposal) {
        //register proposal, compute averages
        for (BookPriceTuple p :
                proposal) {
            double price = p.price;
            if (saleEma.containsKey(p.book))
                price = SMOOTHING_FACTOR * price + (1 - SMOOTHING_FACTOR) * saleEma.get(p.book);
            saleEma.put(p.book, price);
        }

        //filter items
        return proposal.stream()
                .filter(p -> p.price > estimateBookUtility(p.book))
                .collect(Collectors.toList());
    }

    /**
     * Proposes books and prices for sale
     *
     * @return
     */
    public ArrayList<BookPriceTuple> proposeSale() {
        ArrayList<BookPriceTuple> proposal = new ArrayList<>();

        for (Integer b : books) {
            double p = purchaseEma.get(b) * (1 + MARGIN);
            if (goals.containsKey(b))
                p = Math.max(p, goals.get(b) * (1 + MARGIN));

            if (saleEma.containsKey(b))
                p = Math.min(p, saleEma.get(b));

            if ((System.currentTimeMillis() - time) > STOP_TRADING_NONGOAL_BOOKS)
                if (!goals.containsKey(b))
                    p = (TRADING_DURATION - System.currentTimeMillis() + time) * p / TRADING_DURATION;

            proposal.add(new BookPriceTuple(b, p));
        }

        return proposal;
    }

    public void registerSale(ArrayList<BookPriceTuple> sale){
        for (BookPriceTuple b :
                sale) {
            books.remove(b.book);
        }
    }

    public void registerPurchase(ArrayList<BookPriceTuple> purchase){
        for (BookPriceTuple b :
                purchase) {
            books.add(b.book);
        }
    }

    /**
     * Estimates utility for the book
     *
     * @param id
     * @return utility
     */
    private double estimateBookUtility(int id) {
        if (goals.containsKey(id))
            return goals.get(id);

        if ((System.currentTimeMillis() - time) > STOP_TRADING_NONGOAL_BOOKS)
            return 0;

        if ((System.currentTimeMillis() - time) > USE_AVERAGES_AFTER) {
            if (saleEma.containsKey(id))
                return saleEma.get(id);
            else
                return minBookPrice;
        } else
            return minBookPrice;
    }

}
