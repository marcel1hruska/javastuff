package javastuff;

import jade.lang.acl.ACLMessage;
import javastuff.onto.BookInfo;
import javastuff.onto.Goal;
import javastuff.onto.Offer;

import java.util.*;

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
    double money;
    /**
     * Purchase proposals we get / exponential moving average
     */
    HashMap<BookInfo, Double> purchaseEma = new HashMap<>();
    /**
     * Sale proposals / exponential moving average
     */
    HashMap<BookInfo, Double> saleEma = new HashMap<>();
    HashMap<BookInfo, Double> goals = new HashMap<>();
    ArrayList<BookInfo> books = new ArrayList<>();

    private class PriceTimestamp {
        double price;
        long time;

        public PriceTimestamp(long time, double price) {
            this.price = price;
            this.time = time;
        }
    }

    public class BookPriceTuple {
        public BookInfo book;
        public double price;

        public BookPriceTuple(BookInfo book, double price) {
            this.book = book;
            this.price = price;
        }
    }

    public class OfferInfo {
        public Offer offer;
        public double value;
        public ACLMessage response;

        public OfferInfo(Offer offer, double value, ACLMessage response) {
            this.offer = offer;
            this.value = value;
            this.response = response;
        }
    }

    public BookTraderLogic(BookTrader agent) {
        this.agent = agent;
    }

    public void startTrading() {
        time = System.currentTimeMillis();

        goals.clear();
        books.clear();
        money = agent.myMoney;

        for (Goal g : agent.myGoal)
            goals.put(g.getBook(), g.getValue());

        for (BookInfo b : agent.myBooks)
            books.add(b);

        minBookPrice = goals.values().stream().min(Double::compareTo).get();
        maxBookPrice = goals.values().stream().max(Double::compareTo).get();
    }

    enum OfferType {
        PURCHASE,
        SALE
    }

    /**
     * Computes value of an offer
     * Do not forget to add price in his offer!!!
     */
    private double computeOfferValue(Offer o, OfferType type) {
        double p = 0;
        for (BookInfo b :
                o.getBooks()) {
            p += estimateBookUtility(b, type == OfferType.SALE ? Mode.OPTIMISTIC : Mode.PESSIMISTIC);
        }
        if (type == OfferType.PURCHASE)
            return p * (1 - MARGIN);
        else
            return p * (1 + MARGIN);
    }

    /**
     * Proposes books
     *
     * @return
     */
/*
    tato funkcia ma vyrabat tuple knih, ktore chceme, ceny su nam predpokladam naprd
 */
    private int proposedIndex = 0;

    public ArrayList<BookInfo> proposePurchase() {
        ArrayList<BookInfo> proposal = new ArrayList<>();

        /*
        if ((System.currentTimeMillis() - time) < STOP_TRADING_NONGOAL_BOOKS)
            for (BookInfo b : saleEma.keySet()) {
                double p = saleEma.get(b) * (1 - MARGIN);
                if (purchaseEma.containsKey(b))
                    p = Math.min(p, purchaseEma.get(b));
                proposal.add(new BookPriceTuple(b, p));
            }
        */
        if (proposedIndex >= goals.keySet().size())
            proposedIndex = 0;
        int i = 0;
        for (BookInfo b : goals.keySet()) {
            //proposal.add(new BookPriceTuple(b, goals.get(b) * (1 - MARGIN)));
            if (i == proposedIndex)
            {
                proposal.add(b);
                proposedIndex++;
                break;
            }
            i++;
        }

        return proposal;
    }

    /**
     * Computes utility of trade
     * Utility > MARGIN is best
     * Positive value is OK
     * Value can be used for sorting (e.g. in case we get more offers from sale trader)
     *
     * @param heWants
     * @param weWant
     * @return utility; positive good, negative bad
     */
    public double acceptTrade(Offer heWants, Offer weWant) {
        double hisVal = heWants.getMoney();
        //register proposal, compute averages
        if (heWants.getBooks() != null)
        {
            for (BookInfo p :
                    heWants.getBooks()) {
                double price = estimateBookUtility(p, Mode.OPTIMISTIC);
                hisVal += price;
                if (saleEma.containsKey(p))
                    price = SMOOTHING_FACTOR * price + (1 - SMOOTHING_FACTOR) * saleEma.get(p);
                saleEma.put(p, price);
            }
        }

        double ourVal = weWant.getMoney();
        if (weWant.getBooks() != null)
        {
            for (BookInfo p :
                    weWant.getBooks()) {
                double price = estimateBookUtility(p, Mode.PESSIMISTIC);
                ourVal += price;
                if (purchaseEma.containsKey(p))
                    price = SMOOTHING_FACTOR * price + (1 - SMOOTHING_FACTOR) * purchaseEma.get(p);
                purchaseEma.put(p, price);
            }
        }

        return ourVal / hisVal;
    }

    /**
     * Proposes books and prices for sale
     *
     * @return
     */
    public ArrayList<Offer> proposeSale(ArrayList<BookInfo> wanted) {
        ArrayList<Offer> offers = new ArrayList<>();

        //vsetko za peniaze
        Offer o = new Offer();
        o.setBooks(wanted);
        o.setMoney(computeOfferValue(o, OfferType.SALE));
        o.setBooks(new ArrayList<>());
        offers.add(o);

        //vsetko za knihy + peniaze
        o = new Offer();
        o.setBooks(wanted);
        double p = computeOfferValue(o, OfferType.SALE);
        ArrayList<BookInfo> bs = new ArrayList<>();
        for (BookInfo b : saleEma.keySet()) {
            if (p < 0)
                break;
            if (Math.random() < 0.5)
                continue;
            bs.add(b);
            p -= estimateBookUtility(b, Mode.PESSIMISTIC);
        }
        o.setBooks(bs);
        if (p > 0)
            o.setMoney(p);
        offers.add(o);

        return offers;
    }

    void updateLogic() {
        money = agent.myMoney;
        books = agent.myBooks;
    }


    enum Mode {
        OPTIMISTIC,
        PESSIMISTIC
    }

    /**
     * Estimates utility for the book
     *
     * @param id
     * @param mode
     * @return utility
     */
    private double estimateBookUtility(BookInfo id, Mode mode) {
        if (goals.containsKey(id))
            return goals.get(id);

        if ((System.currentTimeMillis() - time) > STOP_TRADING_NONGOAL_BOOKS)
            return 0;

        if ((System.currentTimeMillis() - time) > USE_AVERAGES_AFTER) {
            if (saleEma.containsKey(id))
                return saleEma.get(id);
            else
                return mode == Mode.PESSIMISTIC ? minBookPrice : maxBookPrice;
        } else
            return mode == Mode.PESSIMISTIC ? minBookPrice : maxBookPrice;
    }

}
