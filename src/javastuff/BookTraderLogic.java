package javastuff;

import jade.lang.acl.ACLMessage;
import javastuff.onto.BookInfo;
import javastuff.onto.Goal;
import javastuff.onto.Offer;

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
    HashMap<BookInfo, Double> purchaseEma = new HashMap<>();
    /**
     * Sale proposals / exponential moving average
     */
    HashMap<BookInfo, Double> saleEma = new HashMap<>();
    HashMap<BookInfo, Double> goals = new HashMap<>();
    HashSet<BookInfo> books = new HashSet<>();

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

        for (Goal g : agent.myGoal)
            goals.put(g.getBook(), g.getValue());

        for (BookInfo b : agent.myBooks)
            books.add(b);

        minBookPrice = goals.values().stream().min(Double::compareTo).get();
        maxBookPrice = goals.values().stream().max(Double::compareTo).get();
    }

    /**
     * Computes value of an offer
     *
     */
/*
    dorobit
 */
    public double computeOfferValue(Offer o)
    {
        return 0.0;
    }

    /**
     * Proposes books and prices for purchase
     *
     * @return
     */
/*
    cena teraz nerobi nic, chceme aby nieco robila?
 */
    public ArrayList<BookPriceTuple> proposePurchase() {
        ArrayList<BookPriceTuple> proposal = new ArrayList<>();
        if ((System.currentTimeMillis() - time) < STOP_TRADING_NONGOAL_BOOKS)
            for (BookInfo b : saleEma.keySet()) {
                double p = saleEma.get(b) * (1 - MARGIN);
                if (purchaseEma.containsKey(b))
                    p = Math.min(p, purchaseEma.get(b));
                proposal.add(new BookPriceTuple(b,p));
            }

        for (BookInfo b : goals.keySet()) {
            proposal.add(new BookPriceTuple(b, goals.get(b) * (1 - MARGIN)));
        }

        return proposal;
    }

/*
    ani len netusim kde pouzit tieto accept funkcie :D
 */
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
/*
    musi brat do uvahy co vobec od teba chcu, vyrabas proposal ku cfp

    ideme robit zakazdym len jednu offer?

    DOROBIT, not working, len nacrt
 */
    public ArrayList<Offer> proposeSale(ArrayList<BookInfo> wanted) {
        ArrayList<BookPriceTuple> proposal = new ArrayList<>();

        for (BookInfo b : books) {
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
        /*
        o.setBooks(bis);
        o.setMoney(20);
         */
        Offer o = new Offer();
        ArrayList<Offer> offers = new ArrayList<>();
        offers.add(o);
        return offers;
    }

/*
    EXTREMNE IMPORTANTE!!! my mozme dostat knihy za sale a takisto prist o nejake pri purchase (trade knihu za knihu) - zatial som to mergeol dokopy

    nemali by tieto dve funkcie aj odratavat z nasich penazi? riesime vobec nejako kolko penazi mame?

    chceme aby toto vedelo nieco viac?
 */
    public void registerTrade(ArrayList<BookPriceTuple> sale,ArrayList<BookPriceTuple> purchase){
        for (BookPriceTuple b :
                sale) {
            books.remove(b.book);
        }
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
    private double estimateBookUtility(BookInfo id) {
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
