package example.borrow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import example.borrow.application.CirculationDesk;
import example.borrow.domain.Book;
import example.borrow.domain.BookCheckedOut;
import example.borrow.domain.BookPlacedOnHold;
import example.borrow.domain.BookRepository;
import example.borrow.domain.Hold;
import example.borrow.domain.HoldEventPublisher;
import example.borrow.domain.HoldRepository;
import example.borrow.domain.Patron.PatronId;

import static example.borrow.domain.Book.BookStatus.ISSUED;
import static example.borrow.domain.Book.BookStatus.ON_HOLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CirculationDeskTest {

    CirculationDesk circulationDesk;

    @Mock
    BookRepository bookRepository;

    @Mock
    HoldRepository holdRepository;

    @Mock
    HoldEventPublisher publisher;

    @BeforeEach
    void setUp() {
        circulationDesk = new CirculationDesk(bookRepository, holdRepository, publisher);
    }

    @Test
    void patronCanPlaceHold() {
        var command = new Hold.PlaceHold(new Book.Barcode("12345"), LocalDate.now(), new PatronId(UUID.randomUUID()));
        var book = Book.addBook(new Book.AddBook(new Book.Barcode("12345"), "Test Book", "1234567890"));
        var hold = Hold.placeHold(command);
        when(bookRepository.findAvailableBook(any())).thenReturn(Optional.of(book));
        when(holdRepository.save(any())).thenReturn(hold);
        when(publisher.holdPlaced(any(Hold.class))).thenReturn(hold);

        var holdDto = circulationDesk.placeHold(command);

        assertThat(holdDto.getBookBarcode()).isEqualTo("12345");
        assertThat(holdDto.getDateOfHold()).isNotNull();
    }

    @Test
    void bookStatusUpdatedWhenPlacedOnHold() {
        var command = new Hold.PlaceHold(new Book.Barcode("12345"), LocalDate.now(), new PatronId(UUID.randomUUID()));
        var hold = Hold.placeHold(command);

        var book = Book.addBook(new Book.AddBook(new Book.Barcode("12345"), "Test Book", "1234567890"));
        when(bookRepository.findAvailableBook(any())).thenReturn(Optional.of(book));
        when(bookRepository.save(any())).thenReturn(book);

        circulationDesk.handle(new BookPlacedOnHold(hold.getId().id(), hold.getOnBook().barcode(), hold.getDateOfHold()));

        assertThat(book.getStatus()).isEqualTo(ON_HOLD);
    }

    @Test
    void patronCanCheckoutBook() {
        var patronId = new PatronId(UUID.randomUUID());
        var hold = Hold.placeHold(new Hold.PlaceHold(new Book.Barcode("12345"), LocalDate.now(), patronId));
        var command = new Hold.Checkout(hold.getId(), LocalDate.now(), patronId);

        when(holdRepository.findById(any())).thenReturn(Optional.of(hold));
        when(holdRepository.save(any())).thenReturn(hold);
        when(publisher.bookCheckedOut(any(Hold.class))).thenReturn(hold);

        var checkoutDto = circulationDesk.checkout(command);
        assertThat(checkoutDto.getHoldId()).isEqualTo(hold.getId().id().toString());
        assertThat(checkoutDto.getDateOfCheckout()).isNotNull();
    }

    @Test
    void patronCannotCheckoutBookHeldBySomeoneElse() {
        var hold = Hold.placeHold(new Hold.PlaceHold(new Book.Barcode("12345"), LocalDate.now(), new PatronId(UUID.randomUUID())));
        var command = new Hold.Checkout(hold.getId(), LocalDate.now(), new PatronId(UUID.randomUUID()));

        when(holdRepository.findById(any())).thenReturn(Optional.of(hold));

        assertThatIllegalArgumentException() //
                .isThrownBy(() -> circulationDesk.checkout(command)) //
                .withMessage("Hold does not belong to the specified patron");
    }

    @Test
    void bookStatusUpdatedWhenCheckoutBook() {
        // Arrange
        Book book = Book.addBook(new Book.AddBook(new Book.Barcode("12345"), "Test Book", "1234567890"));
        book.markOnHold();
        when(bookRepository.findOnHoldBook(any())).thenReturn(Optional.of(book));
        when(bookRepository.save(any())).thenReturn(book);
        BookCheckedOut event = new BookCheckedOut(book.getId().id(), book.getInventoryNumber().barcode(), LocalDate.now());

        // Act
        circulationDesk.handle(event);

        // Assert
        assertThat(book.getStatus()).isEqualTo(ISSUED);
    }
}
