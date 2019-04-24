package javastuff.onto;

import jade.content.Concept;
import jade.content.onto.annotations.AggregateSlot;
import jade.content.onto.annotations.Slot;

import java.awt.print.Book;
import java.util.ArrayList;

/**
 * Created by Martin Pilat on 16.4.14.
 *
 * * An offer from the seller, must contain both money and a list of books
 */
public class Offer implements Concept {

    ArrayList<BookInfo> books;
    double money;

    @Slot(mandatory = true)
    public double getMoney() {
        return money;
    }

    public void setMoney(double money) {
        this.money = money;
    }

    @AggregateSlot(cardMin = 0)
    public ArrayList<BookInfo> getBooks() {
        return books;
    }

    public void setBooks(ArrayList<BookInfo> books) {
        this.books = books;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        for (BookInfo b : books)
        {
            result = prime * result + ((b == null) ? 0 : b.hashCode());
        }
        result = prime * result + (int)money;
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Offer other = (Offer) obj;
        if (other.books.size() != books.size())
            return false;
        for (int i = 0; i < books.size(); i++) {
            if (books.get(i) != other.books.get(i))
                return false;
        }
        return money == other.money;
    }
}
