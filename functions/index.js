const functions = require('firebase-functions');

// ====== PHASE 5: MARKET PRICES AGGREGATOR (HTTPS Callable) ======
exports.getMarketPrices = functions.https.onCall((data, context) => {
    // 1. Check Authentication (mandatory for all callable functions)
    if (!context.auth) {
        throw new functions.https.HttpsError('unauthenticated', 'The function must be called by an authenticated user.');
    }

    // 2. Data Aggregation Simulation
    const prices = {
        'Wheat (Average)': 2150,
        'Rice (Basmati)': 4500,
        'Potato (Max)': 1500,
        'Corn (Hybrid)': 1800
    };

    // 3. Return the data as a Map object to the Kotlin app
    return prices;
});
// =============================================================