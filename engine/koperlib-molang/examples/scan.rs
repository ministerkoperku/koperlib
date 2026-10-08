// cargo run --example scan -- file_with_one_expression_per_line
// dev tool: how much of a pack's molang parses, and which queries it wants
use koperlib_molang::Book;
fn main() {
    let path = std::env::args().nth(1).expect("file");
    let text = std::fs::read_to_string(path).unwrap();
    let mut b = Book::default();
    let (mut ok, mut bad) = (0, 0);
    for line in text.lines() {
        let (_, e) = b.compile(line);
        match e {
            None => ok += 1,
            Some(e) => {
                bad += 1;
                if bad <= 40 { println!("FAIL {e}"); }
            }
        }
    }
    println!("ok {ok} bad {bad}");
    let mut q: Vec<String> = b.queries.iter().map(|q| q.split('(').next().unwrap().to_string()).collect();
    q.sort();
    q.dedup();
    println!("queries {}: {}", q.len(), q.join(" "));
}
